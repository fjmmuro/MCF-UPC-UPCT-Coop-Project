cd c:/net2plan-0.5.0.3

for %%m in (example7nodes_withTraffic internet2_N9_E26_withTraffic) do (
	for %%a in (MCF_ILP_UPC_UPCT_Coop MCF_Heursitic_UPC_UPCT_Coop) do (
		for %%r in (fully-non-blocking core-continuity-constraint) do (
			for %%c in (7 12 19) do (
				for %%t in (25 50 75 100 125 150 175 200 225 250 275 300 325 350 375 400 425 450 475 500 525 550 575 600 625 650 675 700 725 750 775) do (
					java -jar Net2Plan-cli.jar --mode net-design ^
					--input-file c:/net2plan-0.5.0.3/workspace/data/networkTopologies/%%m.n2p ^
					--class-file c:/Users/javie/Documents/Git/MCF-UPC-UPCT-Coop-Project_b/out/production/MCF-UPC-UPCT-Coop-Project_b/com/net2plan/general/%%a.class ^
					--class-name %%a ^
					--output-file c:/Users/javie/OneDrive/Projects/output.n2p ^
					--alg-param solverName=cplex ^
					--alg-param numCores=%%c ^
					--alg-param totalTraffic=%%t ^
					--alg-param roadmType=%%r ^
					--alg-param maxSolverTimeInSeconds=1200 ^
					--alg-param k=3 ^
					--alg-param scaleTraffic=true
				)
			)
		)
	)
)