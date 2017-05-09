:: Script for the execution of the ILP vs Heuristic Study in MCF Environtment 

cd c:\Net2Plan-0.5.1-Beta.2-SNAPSHOT

for %%m in (example7nodes_withTraffic_200T internet2_N9_E26_withTraffic_MCF_200T) do (
	for %%a in (MCF_Heuristic_UPC_UPCT_Coop MCF_ILP_UPC_UPCT_Coop) do (
		for %%r in (fully-non-blocking core-continuity-constraint) do (
			for %%c in (7 12 19) do (
				for / %%t in (0,25,850) do (
					java -jar Net2Plan-cli.jar --mode net-design ^
					--input-file c:/Net2Plan-0.5.1-Beta.2-SNAPSHOT/workspace/data/networkTopologies/%%m.n2p ^
					--class-file c:/Users/javie/Documents/Git/MCF-UPC-UPCT-Coop-Project_b/out/production/MCF-UPC-UPCT-Coop-Project_b/com/net2plan/general/%%a.class ^
					--class-name %%a ^
					--output-file c:/Users/javie/OneDrive/Projects/output.n2p ^
					--alg-param solverName=cplex ^
					--alg-param numCores=%%c ^
					--alg-param totalTraffic=%%t ^
					--alg-param roadmType=%%r ^
					--alg-param maxSolverTimeInSeconds=3600 ^
					--alg-param k=5 ^
					--alg-param scaleTraffic=true
				)
			)
		)
	)
)