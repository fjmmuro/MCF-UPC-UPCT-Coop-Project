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
	
	
}
		