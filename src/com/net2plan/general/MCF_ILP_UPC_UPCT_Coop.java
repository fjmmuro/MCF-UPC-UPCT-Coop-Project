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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.jom.OptimizationProblem;
import com.net2plan.interfaces.networkDesign.Demand;
import com.net2plan.interfaces.networkDesign.IAlgorithm;
import com.net2plan.interfaces.networkDesign.Link;
import com.net2plan.interfaces.networkDesign.MulticastDemand;
import com.net2plan.interfaces.networkDesign.MulticastTree;
import com.net2plan.interfaces.networkDesign.Net2PlanException;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.interfaces.networkDesign.Node;
import com.net2plan.interfaces.networkDesign.Route;
import com.net2plan.utils.InputParameter;
import com.net2plan.utils.Quadruple;
import com.net2plan.utils.TimeTrace;
import com.net2plan.utils.Triple;
import com.net2plan.utils.Constants.RoutingType;
import com.net2plan.libraries.GraphUtils;
import com.net2plan.libraries.TrafficMatrixGenerationModels;

import cern.colt.matrix.tdouble.DoubleFactory1D;
import cern.colt.matrix.tdouble.DoubleFactory2D;
import cern.colt.matrix.tdouble.DoubleMatrix1D;
import cern.colt.matrix.tdouble.DoubleMatrix2D;
import cern.jet.math.tdouble.DoubleFunctions;


/** This is a template to be used in the lab work, a starting point for the students to develop their programs
 * 
 */
public class MCF_ILP_UPC_UPCT_Coop implements IAlgorithm
{
	
	final private InputParameter numCores = new InputParameter ("numCores", (int) 7 , "Number of cores per fiber");
	final private InputParameter coreCapacity = new InputParameter ("coreCapacity", (int) 1 , "Capacity in number of slots per Core");
	
	/** The method called by Net2Plan to run the algorithm (when the user presses the "Execute" button)
	 * @param netPlan The input network design. The developed algorithm should modify it: it is the way the new design is returned
	 * @param algorithmParameters Pair name-value for the current value of the input parameters
	 * @param net2planParameters Pair name-value for some general parameters of Net2Plan
	 * @return
	 */
	@Override
	public String executeAlgorithm(NetPlan netPlan, Map<String, String> algorithmParameters, Map<String, String> net2planParameters)
	{
		// Remove all initial demands from the loaded topology
		netPlan.removeAllDemands();
		
		/* Initialize all InputParameter objects defined in this object (this uses Java reflection) */
		InputParameter.initializeAllInputParameterFieldsOfObject(this, algorithmParameters);
		
		/*  Initializations and algorithm loads */
		final int N = netPlan.getNumberOfNodes();
		final int E = netPlan.getNumberOfLinks();
		
		
		/**** ILP Module ****/
		OptimizationProblem op = new OptimizationProblem();
		
		// Set input Parameters
		op.setInputParameter("U", coreCapacity.getInt());
		op.setInputParameter("nCores", numCores.getInt());
		
		op.addDecisionVariable("h", false, new int[]{1,1},0,Double.MAX_VALUE);
		
		op.setObjectiveFunction("maximize", "ln(h12)+ln(h23)+ln(h13)");
		
		// Set Constraints
		op.addConstraint("h12+h13 <= U","Constraint12");
		
		op.solve("ipopt");
		
		if(!op.solutionIsOptimal()) throw new Net2PlanException("An optimal solution was not found");
		
		Double l = op.getPrimalSolution("h12").toValue();
		
		/**** ILP Results Implementation ****/
		
		
		
		
		
		return "Ok!"; // this is the message that will be shown in the screen at the end of the algorithm
	}

	/** Returns a description message that will be shown in the graphical user interface
	 */
	@Override
	public String getDescription()
	{
		return "Here you should return the algorithm description to be printed by Net2Plan graphical user interface";
	}

	
	/** Returns the list of input parameters of the algorithm. For each parameter, you should return a Triple with its name, default value and a description
	 * @return
	 */
	@Override
	public List<Triple<String, String, String>> getParameters()	
	{
		return InputParameter.getInformationAllInputParameterFieldsOfObject(this);
	}
	
}
