package com.net2plan.general;

import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.interfaces.networkDesign.NetworkLayer;
import com.net2plan.interfaces.networkDesign.Route;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.libraries.WDMUtils.RSA;

import cern.colt.matrix.tdouble.DoubleFactory2D;
import cern.colt.matrix.tdouble.DoubleMatrix1D;
import cern.colt.matrix.tdouble.DoubleMatrix2D;

public class MCFUtils 
{
	
	public static void checkResourceAllocationClashingPerCore (NetPlan netPlan, int C ,NetworkLayer ... optionalLayerParameter)
	{
		final int E = netPlan.getNumberOfLinks (optionalLayerParameter);
		DoubleMatrix1D w_f = netPlan.getVectorLinkCapacity(optionalLayerParameter) ;
		final int W = w_f.size () == 0? 0 : (int) w_f.getMaxLocation() [0];

		for(int c = 0; c < C; c++)
		{
			DoubleMatrix2D frequencySlot2FiberOccupancy_se = DoubleFactory2D.dense.make (W,E);
			
			/* The wavelengths above the maximum number of wavelengths of a fiber, are set as occupied */
			for (int e = 0 ; e < E ; e ++) 
				for (int w = (int) w_f.get(e) ; w < W ; w ++) 
					frequencySlot2FiberOccupancy_se.set (e,w,1);
			
			/* Wavlengths occupied by the lightpaths as routes */
			for (Route lpRoute : netPlan.getRoutes(optionalLayerParameter))			
				if(lpRoute.getAttribute("fiberCoreId").equalsIgnoreCase(Integer.toString(c)))	
				{
					if (lpRoute.getOccupiedCapacity() == 0) continue; // not been used now
					WDMUtils.allocateResources(new RSA (lpRoute , false) , frequencySlot2FiberOccupancy_se , null);
				}			
		}
	}
	
	public static String getMFCTranspondersXTAwareInfo (int C)
	{
		String transponders = "";
		
		if (C == 7)		
			transponders += "40 2 2 13851 1; 40 2 2 5937 1; 40 2 2 2289 1; 100 3 3 5540 1; 100 2 2 2375 1; 100 2 2 916 1; 400 5 5 1385 1; 400 4 4 594 1; 400 3 3 229 1 "; 
		else if (C == 12)
			transponders += "40 2 2 12190 1; 40 2 2 3062 1; 40 2 2 726 1; 100 3 3 5540 1; 100 2 2 2375 1; 100 2 2 769 1; 400 5 5 1385 1; 400 4 4 594 1; 400 3 3 229 1 "; 
		else if (C == 19)
			transponders += "40 3 3 4755 1; 40 2 2 13851 1; 40 2 2 5937 1; 40 2 2 2289 1; 100 3 3 5540 1; 100 2 2 2375 1; 100 2 2 916 1; 400 5 5 1385 1; 400 4 4 594 1; 400 3 3 150 1 "; 
		
		return transponders;
		
	}
	
	
}
		