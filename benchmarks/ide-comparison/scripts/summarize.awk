BEGIN {
  OFS = ","
  variants[1] = "baseline"
  variants[2] = "bpg"
  variants[3] = "rainbow"
  variants[4] = "color"
  metrics[1] = "heap_mib"
  metrics[2] = "idle_cpu_s_30s"
  metrics[3] = "near_ms"
  metrics[4] = "far_ms"
  metric_labels[1] = "Heap used MiB"
  metric_labels[2] = "Idle CPU s/30s"
  metric_labels[3] = "Near 1k sync commands ms"
  metric_labels[4] = "Far 1k sync commands ms"
  fatal = 0
}

NR == 1 {
  for (i = 1; i <= NF; i++) column[$i] = i
  required = "protocol_id block attempt slot variant run_id row_valid log_clean heap_used_bytes idle_process_cpu_ns near_ms far_ms severe_count error_count plugin_blame_count exclusion_reason"
  required_count = split(required, required_columns, " ")
  for (i = 1; i <= required_count; i++) {
    name = required_columns[i]
    if (!(name in column)) {
      print "missing required CSV column: " name > "/dev/stderr"
      fatal = 1
    }
  }
  if (fatal) exit 3
  next
}

{
  row_count++
  protocol[row_count] = $(column["protocol_id"])
  block[row_count] = $(column["block"])
  attempt[row_count] = $(column["attempt"]) + 0
  slot[row_count] = $(column["slot"]) + 0
  variant[row_count] = $(column["variant"])
  run_id[row_count] = $(column["run_id"])
  row_valid[row_count] = $(column["row_valid"])
  log_clean[row_count] = $(column["log_clean"])
  heap_bytes[row_count] = $(column["heap_used_bytes"]) + 0
  idle_ns[row_count] = $(column["idle_process_cpu_ns"]) + 0
  near_ms[row_count] = $(column["near_ms"]) + 0
  far_ms[row_count] = $(column["far_ms"]) + 0
  severe[row_count] = $(column["severe_count"]) + 0
  errors[row_count] = $(column["error_count"]) + 0
  blame[row_count] = $(column["plugin_blame_count"]) + 0
  exclusion[row_count] = $(column["exclusion_reason"])

  if (row_count == 1) protocol_id = protocol[row_count]
  if (protocol[row_count] != protocol_id) {
    print "mixed protocol_id values are not supported: " protocol_id " and " protocol[row_count] > "/dev/stderr"
    fatal = 1
  }

  key = block[row_count] SUBSEP attempt[row_count]
  if (!(block[row_count] in block_first_order)) {
    block_first_order[block[row_count]] = row_count
  }
  key_seen[key] = 1
  total_rows[key]++
  if (row_valid[row_count] == "true") {
    valid_rows[key]++
    valid_variant_count[key SUBSEP variant[row_count]]++
    row_index[key SUBSEP variant[row_count]] = row_count
  }
}

function complete_attempt(key, i, v) {
  if (total_rows[key] != 4 || valid_rows[key] != 4) return 0
  for (i = 1; i <= 4; i++) {
    v = variants[i]
    if (valid_variant_count[key SUBSEP v] != 1) return 0
  }
  return 1
}

function metric_value(row, metric_index) {
  if (metric_index == 1) return heap_bytes[row] / 1048576.0
  if (metric_index == 2) return idle_ns[row] / 1000000000.0
  if (metric_index == 3) return near_ms[row]
  return far_ms[row]
}

function csv_escape(value, escaped) {
  escaped = value
  gsub(/"/, "\"\"", escaped)
  return "\"" escaped "\""
}

function md_escape(value, escaped) {
  escaped = value
  gsub(/\|/, "\\|", escaped)
  return escaped
}

function range_cell(median, minimum, maximum, metric_index) {
  if (metric_index <= 2) return sprintf("%.3f [%.3f, %.3f]", median, minimum, maximum)
  return sprintf("%.0f [%.0f, %.0f]", median, minimum, maximum)
}

function delta_cell(delta, ratio, metric_index) {
  if (metric_index <= 2) return sprintf("%+.3f / %.3fx", delta, ratio)
  return sprintf("%+.0f / %.3fx", delta, ratio)
}

END {
  if (fatal) exit 3
  if (row_count == 0) {
    print "input CSV contains no data rows" > "/dev/stderr"
    exit 3
  }

  for (key in key_seen) {
    if (!complete_attempt(key)) continue
    split(key, parts, SUBSEP)
    b = parts[1]
    a = parts[2] + 0
    if (!(b in best_attempt) || a < best_attempt[b]) best_attempt[b] = a
  }

  for (selection = 1; selection <= 3; selection++) {
    found = 0
    for (b in best_attempt) {
      if (!(b in selected_block_set) && (!found || block_first_order[b] < candidate_order)) {
        candidate_block = b
        candidate_order = block_first_order[b]
        found = 1
      }
    }
    if (!found) break
    selected_block[selection] = candidate_block
    selected_attempt[selection] = best_attempt[candidate_block]
    selected_block_set[candidate_block] = 1
    selected_count++
  }

  if (selected_count < 3) {
    print "need 3 complete four-variant blocks; found " selected_count > "/dev/stderr"
    exit 4
  }

  print "# Exploratory performance summary — 3 complete blocks" > md
  print "" > md
  print "> **Exploratory only:** n=3 complete blocks. This record may be cited to explain the release sanity check, but it does not support a promotional performance claim or ranking." > md
  print "" > md
  print "Lower is better for every metric. Heap is whole-IDE JVM post-full-GC used heap, not plugin-retained heap or RSS; idle CPU is whole IDE process CPU over 30 seconds." > md
  print "Caret timers cover synchronous `%goto` command completion; deferred visual updates were not fenced, so these are not direct paint or user-perceived latency measurements." > md
  print "" > md
  print "- Protocol: `" protocol_id "`" > md
  print "- Source: `" source_csv "`" > md
  printf("- Selected complete blocks: ") > md
  for (selection = 1; selection <= 3; selection++) {
    if (selection > 1) printf(", ") > md
    printf("block %s / attempt %d", selected_block[selection], selected_attempt[selection]) > md
  }
  print "" > md
  print "- Complete block definition: exactly one valid row for each of baseline, BPG, Rainbow, and Color in the same attempt." > md
  print "" > md

  print "## Median [min, max]" > md
  print "" > md
  print "| Variant | Heap used MiB | Idle CPU s/30s | Near 1k sync commands ms | Far 1k sync commands ms |" > md
  print "|---|---:|---:|---:|---:|" > md
  print "variant,metric,unit,n,median,min,max" > aggregate

  for (vi = 1; vi <= 4; vi++) {
    v = variants[vi]
    markdown_row = "| " v
    for (mi = 1; mi <= 4; mi++) {
      for (selection = 1; selection <= 3; selection++) {
        key = selected_block[selection] SUBSEP selected_attempt[selection]
        row = row_index[key SUBSEP v]
        values[selection] = metric_value(row, mi)
      }
      minimum = values[1]
      maximum = values[1]
      sum = 0
      for (selection = 1; selection <= 3; selection++) {
        value = values[selection]
        if (value < minimum) minimum = value
        if (value > maximum) maximum = value
        sum += value
      }
      median = sum - minimum - maximum
      markdown_row = markdown_row " | " range_cell(median, minimum, maximum, mi)
      unit = (mi == 1 ? "MiB" : (mi == 2 ? "seconds_per_30s" : "ms"))
      printf("%s,%s,%s,3,%.6f,%.6f,%.6f\n", v, metrics[mi], unit, median, minimum, maximum) > aggregate
    }
    print markdown_row " |" > md
  }

  print "" > md
  print "## Paired per-block contrasts" > md
  print "" > md
  print "Each cell is `BPG − competitor / BPG ÷ competitor`. Negative deltas and ratios below 1 favor BPG." > md
  print "" > md
  print "| Block / attempt | Competitor | Heap Δ MiB / ratio | Idle CPU Δ s / ratio | Near Δ ms / ratio | Far Δ ms / ratio |" > md
  print "|---|---|---:|---:|---:|---:|" > md
  print "block,attempt,competitor,metric,unit,bpg_value,competitor_value,delta,ratio" > paired
  competitors[1] = "rainbow"
  competitors[2] = "color"
  for (selection = 1; selection <= 3; selection++) {
    b = selected_block[selection]
    a = selected_attempt[selection]
    key = b SUBSEP a
    bpg_row = row_index[key SUBSEP "bpg"]
    for (ci = 1; ci <= 2; ci++) {
      competitor = competitors[ci]
      competitor_row = row_index[key SUBSEP competitor]
      paired_row = "| " b " / " a " | " competitor
      for (mi = 1; mi <= 4; mi++) {
        bpg_value = metric_value(bpg_row, mi)
        competitor_value = metric_value(competitor_row, mi)
        delta = bpg_value - competitor_value
        ratio = (competitor_value == 0 ? 0 : bpg_value / competitor_value)
        paired_row = paired_row " | " delta_cell(delta, ratio, mi)
        unit = (mi == 1 ? "MiB" : (mi == 2 ? "seconds_per_30s" : "ms"))
        printf("%s,%d,%s,%s,%s,%.6f,%.6f,%.6f,%.6f\n", b, a, competitor, metrics[mi], unit, bpg_value, competitor_value, delta, ratio) > paired
      }
      print paired_row " |" > md
    }
  }

  print "" > md
  print "## Invalid and log-unclean rows" > md
  print "" > md
  print "Audit scope: every data row in the source CSV, including failed attempts that were not selected." > md
  print "" > md
  print "kind,block,attempt,slot,variant,run_id,severe_count,error_count,plugin_blame_count,exclusion_reason" > audit

  excluded_count = 0
  for (row = 1; row <= row_count; row++) {
    key = block[row] SUBSEP attempt[row]
    if (row_valid[row] == "true" && !complete_attempt(key)) excluded_count++
  }
  print "### Valid rows excluded with an incomplete block (" excluded_count ")" > md
  print "" > md
  if (excluded_count == 0) {
    print "None." > md
  } else {
    print "| Block | Attempt | Slot | Variant | Run ID | Reason |" > md
    print "|---|---:|---:|---|---|---|" > md
    for (row = 1; row <= row_count; row++) {
      key = block[row] SUBSEP attempt[row]
      if (row_valid[row] != "true" || complete_attempt(key)) continue
      print "| " block[row] " | " attempt[row] " | " slot[row] " | " variant[row] " | `" run_id[row] "` | incomplete four-variant block |" > md
      print "excluded_incomplete_block", block[row], attempt[row], slot[row], variant[row], run_id[row], severe[row], errors[row], blame[row], csv_escape("incomplete_four_variant_block") > audit
    }
  }

  print "" > md

  invalid_count = 0
  for (row = 1; row <= row_count; row++) if (row_valid[row] != "true") invalid_count++
  print "### Invalid rows (" invalid_count ")" > md
  print "" > md
  if (invalid_count == 0) {
    print "None." > md
  } else {
    print "| Block | Attempt | Slot | Variant | Run ID | Exclusion reason |" > md
    print "|---:|---:|---:|---|---|---|" > md
    for (row = 1; row <= row_count; row++) {
      if (row_valid[row] == "true") continue
      print "| " block[row] " | " attempt[row] " | " slot[row] " | " variant[row] " | `" run_id[row] "` | " md_escape(exclusion[row]) " |" > md
      print "invalid", block[row], attempt[row], slot[row], variant[row], run_id[row], severe[row], errors[row], blame[row], csv_escape(exclusion[row]) > audit
    }
  }

  print "" > md
  unclean_count = 0
  for (row = 1; row <= row_count; row++) if (log_clean[row] != "true") unclean_count++
  print "### Log-unclean rows (" unclean_count ")" > md
  print "" > md
  if (unclean_count == 0) {
    print "None." > md
  } else {
    print "| Block | Attempt | Slot | Variant | Run ID | SEVERE | ERROR | Plugin blame | Row valid |" > md
    print "|---:|---:|---:|---|---|---:|---:|---:|---|" > md
    for (row = 1; row <= row_count; row++) {
      if (log_clean[row] == "true") continue
      print "| " block[row] " | " attempt[row] " | " slot[row] " | " variant[row] " | `" run_id[row] "` | " severe[row] " | " errors[row] " | " blame[row] " | " row_valid[row] " |" > md
      print "log_unclean", block[row], attempt[row], slot[row], variant[row], run_id[row], severe[row], errors[row], blame[row], csv_escape(exclusion[row]) > audit
    }
  }

  close(md)
  close(aggregate)
  close(paired)
  close(audit)
}
