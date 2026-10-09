def sample_geometry: type == "array" and length == 2 and all(.[]; type == "array" and length == 3 and all(.[]; type == "number" and (isnan or isinfinite | not) and . >= 0));
if type != "array" or length != 8 then error("Expected eight cases") else . end
| map(if .mode != "avgt" or .primaryMetric.scoreUnit != "ns/op" or .secondaryMetrics["gc.alloc.rate.norm"].scoreUnit != "B/op"
    or (.params | keys) != ["distribution", "pairCount"]
    or .benchmark != ("com.sijunyang.bracketpairguides.benchmarks.CalculationContractBenchmark." + $job)
    or .jmhVersion != "1.37" or (.jdkVersion | type) != "string" or (.jdkVersion | test("^17(\\.|$)" ) | not)
    or .forks != 2 or .threads != 1 or .warmupIterations != 2 or .measurementIterations != 3
    or .warmupTime != "1 s" or .measurementTime != "1 s" or .warmupBatchSize != 1 or .measurementBatchSize != 1
    or (.jvmArgs | type) != "array" or ([.jvmArgs[] | select(startswith("-Xms"))] != ["-Xms2g"])
    or ([.jvmArgs[] | select(startswith("-Xmx"))] != ["-Xmx2g"])
    or (.primaryMetric.rawData | sample_geometry | not)
    or (.secondaryMetrics["gc.alloc.rate.norm"].rawData | sample_geometry | not)
    then error("Changed metric contract") else . end)
| if (map(.params.distribution + "|" + .params.pairCount) | sort) != ["malformed|4096","malformed|64","nested|4096","nested|64","siblings|4096","siblings|64","sparse|4096","sparse|64"] then error("Missing or duplicate cases") else . end
| map(if (.primaryMetric.score | type) != "number" or (.secondaryMetrics["gc.alloc.rate.norm"].score | type) != "number"
    or (.primaryMetric.score | isnan or isinfinite) or (.secondaryMetrics["gc.alloc.rate.norm"].score | isnan or isinfinite)
    or .primaryMetric.score < 0 or .secondaryMetrics["gc.alloc.rate.norm"].score < 0 then error("Invalid score") else . end)
| map({key: (.benchmark + " " + (.params | to_entries | sort_by(.key) | from_entries | tojson)),
    value: {latency: {value: .primaryMetric.score}, allocation_bop: {value: .secondaryMetrics["gc.alloc.rate.norm"].score}}})
| from_entries
