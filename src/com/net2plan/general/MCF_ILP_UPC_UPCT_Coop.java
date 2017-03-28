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


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.jom.DoubleMatrixND;
import com.jom.OptimizationProblem;
import com.net2plan.interfaces.networkDesign.*;
import com.net2plan.utils.InputParameter;
import com.net2plan.utils.Pair;
import com.net2plan.utils.Triple;
import com.net2plan.utils.Constants.RoutingType;
import com.net2plan.libraries.TrafficMatrixGenerationModels;
import com.net2plan.libraries.WDMUtils;

import cern.colt.list.tdouble.DoubleArrayList;
import cern.colt.list.tint.IntArrayList;
import cern.colt.matrix.tdouble.DoubleFactory2D;
import cern.colt.matrix.tdouble.DoubleMatrix2D;


/** This is a template to be used in the lab work, a starting point for the students to develop their programs
 *
 */
public class MCF_ILP_UPC_UPCT_Coop implements IAlgorithm
{
	final private InputParameter k = new InputParameter ("k", (int) 5 , "Maximum number of admissible paths per input-output node pair" , 1 , Integer.MAX_VALUE);
	final private InputParameter numCores = new InputParameter ("numCores", "#select# 7 12 19" , "Number of cores per fiber");
	final private InputParameter wdmLayerIndex = new InputParameter ("wdmLayerIndex", (int) 0 , "Index of the WDM layer (-1 means default layer)");
	final private InputParameter solverLibraryName = new InputParameter ("solverLibraryName", "" , "The solver library full or relative path, to be used by JOM. Leave blank to use JOM default.");
	final private InputParameter solverName = new InputParameter ("solverName", "#select# cplex glpk ipopt xpress ", "The solver name to be used by JOM. GLPK and IPOPT are free, XPRESS and CPLEX commercial. GLPK, XPRESS and CPLEX solve linear problems w/w.o integer contraints. IPOPT is can solve nonlinear problems (if convex, returns global optimum), but cannot handle integer constraints");
	final private InputParameter maxSolverTimeInSeconds = new InputParameter ("maxSolverTimeInSeconds", (double) -1 , "Maximum time granted to the solver to solve the problem. If this time expires, the solver returns the best solution found so far (if a feasible solution is found)");
	final private InputParameter numFrequencySlotsPerCore = new InputParameter ("numFrequencySlotsPerCore", (int) 120 , "Number of wavelengths per link" , 1, Integer.MAX_VALUE);
	final private InputParameter maxPropagationDelayMs = new InputParameter ("maxPropagationDelayMs", (double) -1 , "Maximum allowed propagation time of a lighptath in miliseconds. If non-positive, no limit is assumed");
//	final private InputParameter transponderTypesInfo = new InputParameter ("transponderTypesInfo", "10 1 1 4000 1; 10 1 1 6000 1; 10 2 2 7000 1; 40 1 1 3000 1; 40 2 2 4000 1; 40 2 2 5000 1; 100 2 2 1000 1; 100 2 2 2000 1; 100 3 3 3000 1;" , "Transponder types separated by \";\" . Each type is characterized by the space-separated values: (i) Line rate in Gbps, (ii) cost of the transponder, (iii) number of slots occupied in each traversed fiber, (iv) optical reach in km (a non-positive number means no reach limit), (v) cost of the optical signal regenerator (regenerators do NOT make wavelength conversion ; if negative, regeneration is not possible).");
	final private InputParameter ilpType = new InputParameter("ilpType", "#select# fully-non-blocking core-continuity-constraint", "Choose the type of the ILP exection");
	final private InputParameter totalTraffic = new InputParameter("totalTraffic", (double) 200.0, "Total offered traffic ");
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

		final boolean isNotCCC = ilpType.getString().equalsIgnoreCase("fully-non-blocking");

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

		// Initialize lists needed for ILP
		final int maximumNumberOfPaths = T*k.getInt()*D;
		List<Integer> transponderType_p = new ArrayList<Integer> (maximumNumberOfPaths);
		List<Double> cost_p = new ArrayList<Double> (maximumNumberOfPaths);
		List<Double> lineRate_p = new ArrayList<Double> (maximumNumberOfPaths);
		List<Integer> numSlots_p = new ArrayList<Integer> (maximumNumberOfPaths);
		List<Demand> demand_p = new ArrayList<Demand> (maximumNumberOfPaths);
		List<List<Link>> seqLinks_p = new ArrayList<List<Link>>(maximumNumberOfPaths);

		for (Demand d : netPlan.getDemands())
		{
			boolean atLeastOnePath = false;
			Pair<Node,Node> nodes = Pair.of(d.getIngressNode(),d.getEgressNode());

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
					atLeastOnePath = true;
				}
			}
			if (!atLeastOnePath) throw new Net2PlanException ("There are no possible routes for a demand (" + d + "). The topology may be not connected enough, or the optical reach may be too small");
		}
		final int P = transponderType_p.size(); // one per potential sequence of links and transponder

		/* Compute some important matrices for the formulation */

		DoubleMatrix2D A_dp = DoubleFactory2D.sparse.make(D,P); /* 1 is path p is assigned to demand d */
		DoubleMatrix2D A_ep = DoubleFactory2D.sparse.make(E,P); /* 1 if path p travserses link e */

		double [][] feasibleAssignment_ps = new double [P][S];

		for (int p = 0; p < P; p++)
		{
			A_dp.set(demand_p.get(p).getIndex(), p, 1.0);
			for (Link e : seqLinks_p.get(p)) A_ep.set (e.getIndex() , p , 1.0);
			for (int s = 0; s < S + 1 - numSlots_p.get(p) ; s ++)
				feasibleAssignment_ps [p][s] = 1;
		}

		/* Create the optimization problem object (JOM library) */
		OptimizationProblem op = new OptimizationProblem();

		/* Add the decision variables to the problem */
		if (isNotCCC)
			op.addDecisionVariable("x_ps", true, new int[] {P, S}, new DoubleMatrixND (new int [] {P,S}) , new DoubleMatrixND (feasibleAssignment_ps)); /* 1 if lightpath d(p) is routed through path p in wavelength w */
		else
			for (int c = 0 ; c < C; c++)
				op.addDecisionVariable("x_ps"+Integer.toString(c), true, new int[] {P, S}, new DoubleMatrixND (new int [] {P,S}) , new DoubleMatrixND (feasibleAssignment_ps));

		// Set input Parameters
		op.setInputParameter("S", S);
		op.setInputParameter("C", C);
		op.setInputParameter("h_d", netPlan.getVectorDemandOfferedTraffic(), "row");
		op.setInputParameter("rate_p", lineRate_p , "row");
		op.setInputParameter("c_p", cost_p , "row");
		op.setInputParameter("A_dp", A_dp);
		op.setInputParameter("A_ep", A_ep); //Equal to route and segment indexes

		// Set Objective Function
		if(isNotCCC) op.setObjectiveFunction("minimize", "sum(c_p * x_ps)"); /* sum_ps (c_p . x_ps) */
		else
		{
			String objectiveFuntion = "";
			for (int c = 0; c < C ; c++)
				objectiveFuntion += (c == 0? "" : " + ") + "sum(c_p * x_ps" + Integer.toString(c) + ")" ;
			op.setObjectiveFunction("minimize", objectiveFuntion);     /* sum_ps (c_p . x_pcs) */
		}

		if(isNotCCC) op.addConstraint("A_dp * diag (rate_p) * x_ps * ones([S; 1]) >= h_d'"); /* each lightpath d: is carried in exactly one p-w --> sum_{p in P_d, w} x_dp <= 1, for all d */
		else
		{
			String constraintString = "";
			for (int c = 0; c < C; c++)
				constraintString += (c == 0? "" : " + ") + "A_dp * diag (rate_p) * x_ps" + Integer.toString(c) + " * ones([S; 1]) " ;
			op.addConstraint(constraintString +" >= h_d'");
		}

		/* Frequency-slot clashing */
		/* \sum_t \sum_{p \in P_e, sinit {s-numSlots(t),s} x_ps <= 1, for each e, s   */
		if(isNotCCC)
		{
			String constraintString = "";
			for (int t = 0; t < T ; t ++)
			{
				final String name_At_pp = "A" + Integer.toString(t) + "_pp";
				final String name_At_s1s2 = "A" + Integer.toString(t) + "_s1s2";
				/* At_pp, diagonal matrix, 1 if path p is associated to a transponder of type t */
				DoubleMatrix2D At_pp = DoubleFactory2D.sparse.make(P,P);
				int p = 0; for (int type : transponderType_p) { if (type == t) At_pp.set(p,p,1.0); p++; }
				/* At_s1s2, upper triangular matrix, 1 if a transponder of type with initial slot s1, occupied slots s2 (depends on number of slots occupied) */
				DoubleMatrix2D At_s1s2 = DoubleFactory2D.sparse.make(S,S);
				for (int s1 = 0 ; s1 < S ; s1 ++)
					for (int cont = 0 ; cont < tpInfo.getNumSlots(t) ; cont ++)
						if (s1 - cont >= 0) At_s1s2.set(s1-cont,s1,1.0);
				op.setInputParameter(name_At_pp, At_pp);
				op.setInputParameter(name_At_s1s2, At_s1s2);
				constraintString += (t == 0? "" : " + ") + "( A_ep * " + name_At_pp + " * x_ps * " + name_At_s1s2 + " ) ";

			}
			op.addConstraint(constraintString + " <= C"); /* wavelength-clashing constraints --> sum_{p in P_e, w} x_pw <= C, for all e,w */
		}
		else
		{
			for(int c = 0; c < C; c++)
			{
				String constraintString = "";
				for (int t = 0; t < T ; t ++)
				{
					final String name_At_pp = "A" + Integer.toString(t) + "_pp";
					final String name_At_s1s2 = "A" + Integer.toString(t) + "_s1s2";
					/* At_pp, diagonal matrix, 1 if path p is associated to a transponder of type t */
					DoubleMatrix2D At_pp = DoubleFactory2D.sparse.make(P,P);
					int p = 0; for (int type : transponderType_p) { if (type == t) At_pp.set(p,p,1.0); p++; }
					/* At_s1s2, upper triangular matrix, 1 if a transponder of type with initial slot s1, occupied slots s2 (depends on number of slots occupied) */
					DoubleMatrix2D At_s1s2 = DoubleFactory2D.sparse.make(S,S);
					for (int s1 = 0 ; s1 < S ; s1 ++)
						for (int cont = 0 ; cont < tpInfo.getNumSlots(t) ; cont ++)
							if (s1 - cont >= 0) At_s1s2.set(s1-cont,s1,1.0);
					op.setInputParameter(name_At_pp, At_pp);
					op.setInputParameter(name_At_s1s2, At_s1s2);
					constraintString += (t == 0? "" : " + ") + "( A_ep * " + name_At_pp + " * x_ps"+Integer.toString(c)+" * " + name_At_s1s2 + " ) ";

				}
				op.addConstraint(constraintString + " <= 1"); /* wavelength-clashing constraints --> sum_{p in P_e, w} x_pw <= C, for all e,w */
			}
		}

		op.solve(solverName.getString(), "solverLibraryName", solverLibraryName.getString() , "maxSolverTimeInSeconds" , maxSolverTimeInSeconds.getDouble());

		/* If a feasible solution was not found, quit (this may also happen if after the maximum solver time no feasible solution is found) */
		if (!op.solutionIsFeasible()) throw new Net2PlanException("A feasible solution was not found");

		/* Retrieve the optimum solutions */
		DoubleMatrix2D x_ps = DoubleFactory2D.sparse.make(P,S);
		List<DoubleMatrix2D> x_psc = new ArrayList<DoubleMatrix2D>();

		if(isNotCCC) x_ps = op.getPrimalSolution("x_ps").view2D();
		else
		{
			for (int c = 0; c < C; c++)
			{
				String name = "x_ps"+Integer.toString(c);
				x_psc.add(op.getPrimalSolution(name).view2D());
			}
		}

		/* Create the lightpaths according to the solutions given */
		WDMUtils.setFibersNumFrequencySlots(netPlan , numFrequencySlotsPerCore.getInt() , wdmLayer);
		IntArrayList slots = new IntArrayList (); DoubleArrayList vals = new DoubleArrayList ();

		for (int p = 0; p < P ; p ++)
		{
			if(isNotCCC)
			{
				slots.clear(); vals.clear();
				x_ps.viewRow(p).getNonZeros(slots , vals);

				if (slots.size() == 0) continue;
				for (int cont = 0 ; cont < slots.size() ; cont ++)
				{
					final int s = slots.get (cont);
					WDMUtils.addLightpath(demand_p.get(p) , new WDMUtils.RSA(seqLinks_p.get(p) , s , numSlots_p.get(p)), lineRate_p.get(p));
				}
			}
			else
			{
				for (int c = 0; c < C; c++)
				{
					slots.clear(); vals.clear();
					x_psc.get(c).viewRow(p).getNonZeros(slots , vals);

					if (slots.size() == 0) continue;
					for (int cont = 0 ; cont < slots.size() ; cont ++)
					{
						final int s = slots.get (cont);
						Route r = WDMUtils.addLightpath(demand_p.get(p) , new WDMUtils.RSA(seqLinks_p.get(p) , s , numSlots_p.get(p)), lineRate_p.get(p));
						r.setAttribute("fiberCoreID", Integer.toString(c));
					}
				}
				for (Demand d : netPlan.getDemands())
				{
					String coreIDs = "";
					for (Route r : d.getRoutes())
						coreIDs += r.getAttribute("fiberCoreID") + " ";
					d.setAttribute("fiberCoreIDs", coreIDs);
				}
			}
		}

		// Check Spectrum Clashing
		if (isNotCCC)
			if (C == 1)	WDMUtils.checkResourceAllocationClashing(netPlan,false,false,wdmLayer);
		else if (ilpType.getString().equalsIgnoreCase("core-continuity-constraint"))
			MCFUtils.checkResourceAllocationClashingPerCore(netPlan, C);

		// Store results
		final double throughput = netPlan.getVectorRouteCarriedTraffic().zSum();
		final double totalFSOccupied = netPlan.getVectorLinkOccupiedCapacity().zSum();
		final double alpha = totalTraffic.getDouble();
		final double totalOfferedTraffic = netPlan.getVectorDemandOfferedTraffic().zSum();

		File file = new File(netPlan.getNetworkName()+ilpType.getString()+".txt");
		if (!file.exists()){
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			FileWriter fw = new FileWriter(file,true);
			fw.write(Integer.toString(C) + " " + throughput + " " + totalFSOccupied+ " " + totalOfferedTraffic + " " + alpha + "\r\n");
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return "Offered Traffic: " + throughput +" - Throughut (Gbps): " + throughput + " - Total FSOccupied : " + totalFSOccupied ; // this is the message that will be shown in the screen at the end of the algorithm
	}

	/** Returns a description message that will be shown in the graphical user interface
	 */
	@Override
	public String getDescription()
	{
		return "Formulation-Based RSMA Algorithm availables : Non Core Continuity Constraint, Core Continuity Constraint";
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

	
}
