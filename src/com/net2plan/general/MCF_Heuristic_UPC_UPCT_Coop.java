/*******************************************************************************
 * Copyright (c) 2016 Francisco Javier Moreno Muro
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 * 
 * Contributors:
 *     Francisco Javier Moreno Muro
 ******************************************************************************/

package com.net2plan.general;


import cern.colt.matrix.tdouble.DoubleFactory2D;
import cern.colt.matrix.tdouble.DoubleMatrix2D;
import com.net2plan.interfaces.networkDesign.*;
import com.net2plan.libraries.TrafficMatrixGenerationModels;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.*;
import com.net2plan.utils.Constants.RoutingType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;


/** This is a template to be used in the lab work, a starting point for the students to develop their programs
 *
 */
public class MCF_Heuristic_UPC_UPCT_Coop implements IAlgorithm
{
	final private InputParameter k = new InputParameter ("k", (int) 5 , "Maximum number of admissible paths per input-output node pair" , 1 , Integer.MAX_VALUE);
	final private InputParameter numCores = new InputParameter ("numCores", "#select# 7 12 19" , "Number of cores per fiber");
	final private InputParameter wdmLayerIndex = new InputParameter ("wdmLayerIndex", (int) 0 , "Index of the WDM layer (-1 means default layer)");
    final private InputParameter numFrequencySlotsPerCore = new InputParameter ("numFrequencySlotsPerCore", (int) 120 , "Number of wavelengths per link" , 1, Integer.MAX_VALUE);
	final private InputParameter maxPropagationDelayMs = new InputParameter ("maxPropagationDelayMs", (double) -1 , "Maximum allowed propagation time of a lighptath in miliseconds. If non-positive, no limit is assumed");
	final private InputParameter roadmType = new InputParameter("roadmType", "#select# fully-non-blocking core-continuity-constraint", "Choose the type of the ROADM type");
	final private InputParameter totalTraffic = new InputParameter("totalTraffic", (double) 200.0, "Total offered traffic in the network in Tbps ");
	final private InputParameter scaleTraffic = new InputParameter("scaleTraffic", (boolean) true , "Option to scale the traffic using traffic factor ");

	/** The method called by Net2Plan to run the algorithm (when the user presses the "Execute" button)
	 * @param netPlan The input network design. The developed algorithm should modify it: it is the way the new design is returned
	 * @param algorithmParameters Pair name-value for the current value of the input parameters
	 * @param net2planParameters Pair name-value for some general parameters of Net2Plan
	 * @return
	 */


	@Override
	public String executeAlgorithm(NetPlan netPlan, Map<String, String> algorithmParameters, Map<String, String> net2planParameters)
	{
		/* Initialize all InputParameter objects defined in this object (this uses Java reflection) */
		InputParameter.initializeAllInputParameterFieldsOfObject(this, algorithmParameters);

		final NetworkLayer wdmLayer = wdmLayerIndex.getInt () == -1? netPlan.getNetworkLayerDefault() : netPlan.getNetworkLayer(wdmLayerIndex.getInt ());

		/* Basic checks */
		final int N = netPlan.getNumberOfNodes();
		final int E = netPlan.getNumberOfLinks(wdmLayer);
		final int D = netPlan.getNumberOfDemands(wdmLayer);
		final int C = Integer.parseInt(numCores.getString());
		final int S = numFrequencySlotsPerCore.getInt();

		if (N == 0 || E == 0 || D == 0 || S == 0 || C == 0) throw new Net2PlanException("This algorithm requires a topology with links, slots and a demand set");

		final boolean isNotCCC = roadmType.getString().equalsIgnoreCase("fully-non-blocking");

		/* Remove all routes in current netPlan object. Initialize link capacities and attributes, and demand offered traffic */
		netPlan.removeAllMulticastTrees(wdmLayer);
		netPlan.removeAllUnicastRoutingInformation(wdmLayer);
		netPlan.setRoutingType(RoutingType.SOURCE_ROUTING , wdmLayer);

		// Store transponder info
		WDMUtils.TransponderTypesInfo tpInfo = new WDMUtils.TransponderTypesInfo(MCFUtils.getMFCTranspondersXTAwareInfo(C));
		final int T = tpInfo.getNumTypes();

		if(scaleTraffic.getBoolean())
		{
			DoubleMatrix2D newTrafficMatrix = TrafficMatrixGenerationModels.normalizationPattern_totalTraffic(netPlan.getMatrixNode2NodeOfferedTraffic(), 1000*totalTraffic.getDouble());
			netPlan.setTrafficMatrix(newTrafficMatrix);
		}
		// Compute the candidate path list
		final  Map<Pair<Node, Node>, List<List<Link>>> cpl = netPlan.computeUnicastCandidatePathList(netPlan.getVectorLinkLengthInKm(wdmLayer), k.getInt(), tpInfo.getMaxOpticalReachKm(),-1, maxPropagationDelayMs.getDouble(),-1,-1,-1,null,wdmLayer);
		WDMUtils.setFibersNumFrequencySlots(netPlan , numFrequencySlotsPerCore.getInt() , wdmLayer);

		// Initialize lists needed for ILP
		final int maximumNumberOfPaths = T*k.getInt()*D;
		List<Integer> transponderType_p = new ArrayList<Integer> (maximumNumberOfPaths);
		List<Double> cost_p = new ArrayList<Double> (maximumNumberOfPaths);
		List<Double> lineRate_p = new ArrayList<Double> (maximumNumberOfPaths);
		List<Integer> numSlots_p = new ArrayList<Integer> (maximumNumberOfPaths);
		List<Demand> demand_p = new ArrayList<Demand> (maximumNumberOfPaths);
		List<List<Link>> seqLinks_p = new ArrayList<List<Link>>(maximumNumberOfPaths);
		Map<Demand,List<Integer>> demand2PathListMap = new HashMap<Demand,List<Integer>> ();

		for (Demand d : netPlan.getDemands())
		{
			boolean atLeastOnePath = false;
			Pair<Node,Node> nodes = Pair.of(d.getIngressNode(),d.getEgressNode());
			List<Integer> pathListThisDemand = new LinkedList<Integer> ();
			demand2PathListMap.put(d , pathListThisDemand);

			for (int t = 0; t < T; t++)
			{
				for (List<Link> path : cpl.get(nodes))
				{
					if ((getLengthInKm(path) > tpInfo.getOpticalReachKm(t))) break;
					cost_p.add(tpInfo.getCost(t));
					transponderType_p.add(t);
					lineRate_p.add(tpInfo.getLineRateGbps(t));
					numSlots_p.add(tpInfo.getNumSlots(t));
					demand_p.add(d);
					seqLinks_p.add(path);
					final int pathIndex = cost_p.size()-1;
					pathListThisDemand.add(pathIndex);
					atLeastOnePath = true;
				}
			}
			if (!atLeastOnePath) throw new Net2PlanException ("There are no possible routes for a demand (" + d + "). The topology may be not connected enough, or the optical reach may be too small");
		}
		final int P = transponderType_p.size(); // one per potential sequence of links and transponder

		DoubleMatrix2D frequencySlot2FiberOccupancy_se = DoubleFactory2D.dense.make(S*C , E);
		List<DoubleMatrix2D> frequencySlot2FiberOccupancy_sec = new ArrayList<>(C);
		if(!isNotCCC)
			for (int c = 0; c < C; c++)
				frequencySlot2FiberOccupancy_sec.add(DoubleFactory2D.dense.make(S, E));

		boolean atLeastOneLpAdded = false;

		Set<Integer> demandIndexesNotToTry = new HashSet<> ();

		do
		{
			double [] b_d = getVectorDemandAverageAllStatesBlockedTraffic (netPlan);
			int [] demandIndexes = DoubleUtils.sortIndexes(b_d , Constants.OrderingType.DESCENDING);
			atLeastOneLpAdded = false;

			for (int demandIndex : demandIndexes)
			{
				final Demand d = netPlan.getDemand(demandIndex , wdmLayer);

				/* Not to try a demand if already fully satisfied or we tried and could not add a lp to it */
				if (demandIndexesNotToTry.contains(demandIndex)) continue;

				/* If the demand is already fully satisfied, skip it */
				if (d.getBlockedTraffic() < 1e-3) {
					demandIndexesNotToTry.add(demandIndex); continue;
				}
				/* Try all the possible routes and all the possible transponder types. Take the solution with the best
				 * performance metric (average extra carried traffic / transponder cost) */
				WDMUtils.RSA best_rsa = null;
				double best_performanceMetric = 0;
				int best_pathIndex = -1;
				int best_core = -1;
				int best_slotID = -1;
				List<Link> best_route = null;

				for (int pathIndex : demand2PathListMap.get (d))
				{
//                    if(d.getIndex() == 0) System.out.println("Feasible Path per demand 0: " + demand2PathListMap.get(d).size() );

                    List<Link> path = seqLinks_p.get(pathIndex);
					int slotId = -1;
					int current_core = -1;

					if(isNotCCC)
						slotId = WDMUtils.spectrumAssignment_firstFit(path,frequencySlot2FiberOccupancy_se , numSlots_p.get(pathIndex));
					else
					{
						for(int c = 0; c < C; c++)
						{
							slotId = WDMUtils.spectrumAssignment_firstFit(path, frequencySlot2FiberOccupancy_sec.get(c), numSlots_p.get(pathIndex));
							current_core = c;
							if (slotId != -1) break;
						}
					}
					/* Check if the path is not feasible */
					if (slotId == -1) continue;

					/* If the performance metric is better than existing, this is the best choice */
					final double extraCarriedTraffic = Math.min(d.getBlockedTraffic() , lineRate_p.get(pathIndex));
					final double performanceIndicator = extraCarriedTraffic / (cost_p.get(pathIndex) );
					if (performanceIndicator > best_performanceMetric)
					{
						best_performanceMetric = performanceIndicator;
						best_rsa = new WDMUtils.RSA(path , slotId , numSlots_p.get(pathIndex) , null);
						best_core = current_core;
						best_pathIndex = pathIndex;
					}

//                    if(d.getIndex() == 0 && d.getBlockedTraffic() < 101)
//                    {
//                        System.out.println();
//                        System.out.println("-------------- Path Index ------------: " + pathIndex);
//                        System.out.println("Path: " + path);
//                        System.out.println("Cost: " + cost_p.get(pathIndex) );
//                        System.out.println("Num Slots: " + numSlots_p.get(pathIndex));
//                        System.out.println("Line Rates: " + lineRate_p.get(pathIndex) );
//                        System.out.println("performanceIndicator: " + performanceIndicator);
//                        System.out.println("best_performanceMetric:  " + best_performanceMetric);
//                    }
				}

				/* No lp could be added to this demand, try with the next */
				if (best_pathIndex == -1) { demandIndexesNotToTry.add(d.getIndex()); continue; }

				/* Add the lightpath to the design */
				atLeastOneLpAdded = true;

				if(isNotCCC)
				{
					final Route lp = WDMUtils.addLightpath(d , best_rsa , lineRate_p.get(best_pathIndex));
					WDMUtils.allocateResources(best_rsa , frequencySlot2FiberOccupancy_se , null);
				}
				else
				{
					if (best_core != -1)
					{
						final Route lp = WDMUtils.addLightpath(d, best_rsa, lineRate_p.get(best_pathIndex));
						WDMUtils.allocateResources(best_rsa, frequencySlot2FiberOccupancy_sec.get(best_core), null);
						lp.setAttribute("coreID = ", Integer.toString(best_core));
					}
				}

				break;
			}

		} while (atLeastOneLpAdded);

		// Check Spectrum Clashing
		if (isNotCCC)
			if (C == 1)	WDMUtils.checkResourceAllocationClashing(netPlan,false,false,wdmLayer);
		else if (roadmType.getString().equalsIgnoreCase("core-continuity-constraint"))
			MCFUtils.checkResourceAllocationClashingPerCore(netPlan, C);

		// Store results
		final double throughput = netPlan.getVectorRouteCarriedTraffic().zSum();
		final double totalFSOccupied = netPlan.getVectorLinkOccupiedCapacity().zSum();
		final double totalOfferedTraffic = netPlan.getVectorDemandOfferedTraffic().zSum();

		if (!netPlan.getDemandsBlocked().isEmpty()) throw new Net2PlanException("Maximum traffic limit reached");

		File file = new File("heuristic_"+ netPlan.getNetworkName()+ roadmType.getString()+".txt");
		if (!file.exists()){
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			FileWriter fw = new FileWriter(file,true);
			fw.write(Integer.toString(C) + " " + throughput + " " + totalFSOccupied+ " " + totalOfferedTraffic + " \r\n");
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return "Offered Traffic: " + throughput +" - Throughput (Gbps): " + throughput + " - Total FSOccupied : " + totalFSOccupied ; // this is the message that will be shown in the screen at the end of the algorithm
	}

	/** Returns a description message that will be shown in the graphical user interface
	 */
	@Override
	public String getDescription()
	{
		return "ROADMs types availables : Fully-Non-Blocking, Core Continuity Constraint";
	}

	
	/** Returns the list of input parameters of the algorithm. For each parameter, you should return a Triple with its name, default value and a description
	 * @return
	 */
	@Override
	public List<Triple<String, String, String>> getParameters()	
	{
		return InputParameter.getInformationAllInputParameterFieldsOfObject(this);
	}

	private static double getLengthInKm (Collection<Link> r) 
	{
		double res = 0;
		for (Link e : r)
			res += e.getLengthInKm();
		return res; 
	}

	/* A vector with the blocked traffic for each demand (in the single-SRG failure tolerance, is averaged for each state) */
	private double [] getVectorDemandAverageAllStatesBlockedTraffic (NetPlan netPlan)
	{
		double [] res = new double [netPlan.getNumberOfDemands()];
		for (Demand d : netPlan.getDemands())
			res [d.getIndex()] = d.getBlockedTraffic() / d.getOfferedTraffic();

		return res;
	}

	
}
