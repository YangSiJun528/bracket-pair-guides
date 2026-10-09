# Official expanded JsonReport contract: https://bencher.dev/download/openapi.json
# Empty alerts are not evidence that a threshold had a comparable baseline.
def finite_nonnegative: type == "number" and (isnan or isinfinite | not) and . >= 0;
def comparable:
  .threshold.model.test == "percentage" and .threshold.model.upper_boundary == 0.20
  and .threshold.model.max_sample_size == 1
  and (.boundary.baseline | finite_nonnegative)
  and (.boundary.upper_limit | finite_nonnegative)
  and ((.boundary.upper_limit - .boundary.baseline * 1.20) | fabs) <= ([1e-9, (.boundary.baseline | fabs) * 1e-9] | max);
def comparison:
  if (.metrics | type) == "array" then
    [.metrics[] | select(.name == "value") | .boundaries[] | select(comparable)] | length == 1
  else {threshold: .threshold, boundary: .boundary} | comparable end;
. as $report
| if .uuid != $reportUuid or .job != $jobUuid or .project.uuid != $projectUuid
     or .branch.name != $branch or .testbed.slug != "bencher-intel-v1-jdk17"
  then error("Report/job/project/branch/testbed identity mismatch") else . end
| if (.results | type) != "array" or (.results | length) != 1 or (.results[0] | length) != 8
     or (.alerts | type) != "array" then error("Missing expanded one-iteration report") else . end
| [.results[0][] | .benchmark.name] as $actual
| ($bmf[0] | keys) as $expected
| if ($actual | sort) != $expected then error("Report benchmark identities differ from actual BMF") else . end
| if all(.results[0][]; . as $case |
    (.measures | length) == 2
    and ([.measures[].measure.uuid] | sort) == ([$latencyUuid, $allocationUuid] | sort)
    and all(.measures[]; . as $measure |
      (if .measure.uuid == $latencyUuid then "latency" else "allocation_bop" end) as $key
      | ($bmf[0][$case.benchmark.name][$key].value) as $submitted
      | (if (.metrics | type) == "array" then [.metrics[] | select(.name == "value") | .value] else [.metric.value] end) as $values
      | ($values | length) == 1 and ($values[0] | finite_nonnegative)
        and (($values[0] - $submitted) | fabs) <= ([1e-9, ($submitted | fabs) * 1e-9] | max)
        and ($seed == "true" or ($measure | comparison))))
  then {comparative: ($seed != "true"), mode: (if $seed == "true" then "explicit-baseline-seed" else "strict-history-comparison" end),
        caseCount: 8, measureCount: 16, allComputedBoundariesPresent: ($seed != "true")}
  else error("Missing comparable baseline, strict 20-percent/latest-1 threshold, or metric identity/value; explicit baseline seeding required") end
