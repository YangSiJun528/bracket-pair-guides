# Local comparison evidence

Status: main judgment required; no automatic performance pass.

JMH: six paired invocations. Each case/run estimate is the JMH mean score over 2 forks x 3 measurement iterations, not an individual JVM median. Forks and iterations are not additional independent pairs. The internal median field is only a shared storage slot.

No fork/trial pooling. Allocation scopes must not be summed. Table ratios and deltas are summarized across six paired run estimates.

| Metric (JMH internal median = mean score; SDK = trial summary) | Median of six paired C/B ratios | Median of six paired estimate deltas | Pairs increased / 6 |
|---|---:|---:|---:|
| analyzeCold|malformed|4096|allocation_bop|median | 0.9996176605808073 | -317.2083340016543 | 0 |
| analyzeCold|malformed|4096|latency|median | 1.0185852980264494 | 5803.839681333338 | 5 |
| analyzeCold|malformed|64|allocation_bop|median | 0.9883321861336456 | -136.00039239112084 | 0 |
| analyzeCold|malformed|64|latency|median | 0.9891817584794986 | -38.82821878383402 | 2 |
| analyzeCold|nested|4096|allocation_bop|median | 0.9998282953859529 | -289.1021312826779 | 0 |
| analyzeCold|nested|4096|latency|median | 0.9999526431409405 | -41.902860317437444 | 3 |
| analyzeCold|nested|64|allocation_bop|median | 0.9980828303818464 | -47.998279768333305 | 0 |
| analyzeCold|nested|64|latency|median | 1.0233250487217442 | 270.27245159381346 | 5 |
| analyzeCold|siblings|4096|allocation_bop|median | 1.0193763898697559 | 55319.52773826104 | 6 |
| analyzeCold|siblings|4096|latency|median | 1.0050629735610612 | 6067.9589764720295 | 3 |
| analyzeCold|siblings|64|allocation_bop|median | 1.0161595111271446 | 761.2873222959388 | 6 |
| analyzeCold|siblings|64|latency|median | 1.0358293288986777 | 509.9873587835664 | 6 |
| analyzeCold|sparse|4096|allocation_bop|median | 1.0169040820019055 | 8798.488343455945 | 6 |
| analyzeCold|sparse|4096|latency|median | 1.5447347346792357 | 184045.24669852227 | 6 |
| analyzeCold|sparse|64|allocation_bop|median | 1.004121905267768 | 53.99649690743172 | 4 |
| analyzeCold|sparse|64|latency|median | 0.9733947906722773 | -199.1452867064395 | 2 |
| analyzeReuse|malformed|4096|allocation_bop|median | 0.9998852947841779 | -95.10559131018817 | 0 |
| analyzeReuse|malformed|4096|latency|median | 1.0369049081570483 | 11226.123757382826 | 5 |
| analyzeReuse|malformed|64|allocation_bop|median | 1.0079022089747252 | 87.9991677608914 | 6 |
| analyzeReuse|malformed|64|latency|median | 0.9493762387428413 | -183.61324833093022 | 1 |
| analyzeReuse|nested|4096|allocation_bop|median | 0.9999796528856437 | -34.24831472884398 | 0 |
| analyzeReuse|nested|4096|latency|median | 1.00260254681955 | 2452.3606173070148 | 5 |
| analyzeReuse|nested|64|allocation_bop|median | 1.008407360421363 | 206.00132121820934 | 6 |
| analyzeReuse|nested|64|latency|median | 1.0291654866318711 | 343.91892208156696 | 6 |
| analyzeReuse|siblings|4096|allocation_bop|median | 1.0208289195677902 | 59423.57414237759 | 6 |
| analyzeReuse|siblings|4096|latency|median | 0.9769748292430248 | -29793.69176430942 | 1 |
| analyzeReuse|siblings|64|allocation_bop|median | 1.0211112978944445 | 984.1364833586231 | 6 |
| analyzeReuse|siblings|64|latency|median | 1.0390412073919721 | 570.2088627432286 | 6 |
| analyzeReuse|sparse|4096|allocation_bop|median | 1.017393690311709 | 9044.436426951026 | 6 |
| analyzeReuse|sparse|4096|latency|median | 1.5219546723310096 | 181289.11461936953 | 6 |
| analyzeReuse|sparse|64|allocation_bop|median | 1.0197326478238873 | 248.0007857044693 | 6 |
| analyzeReuse|sparse|64|latency|median | 0.992836156511326 | -52.71283519901681 | 3 |
| cancelledAttempt|malformed|4096|allocation_bop|median | 1.4303142361927685 | 20872.01113611864 | 6 |
| cancelledAttempt|malformed|4096|latency|median | 1.0739074257056365 | 1439.6296854080774 | 6 |
| cancelledAttempt|malformed|64|allocation_bop|median | 1.0126536307746719 | 151.9956627015972 | 6 |
| cancelledAttempt|malformed|64|latency|median | 0.8496198249898719 | -726.810608561296 | 0 |
| cancelledAttempt|nested|4096|allocation_bop|median | 1.3042323093833699 | 28376.394444984136 | 6 |
| cancelledAttempt|nested|4096|latency|median | 1.319738931351151 | 6870.730496736507 | 6 |
| cancelledAttempt|nested|64|allocation_bop|median | 1.008274839095435 | 120.00190917381497 | 6 |
| cancelledAttempt|nested|64|latency|median | 1.0657355499409147 | 335.7132722801575 | 6 |
| cancelledAttempt|siblings|4096|allocation_bop|median | 1.026370215574275 | 233.99855090857545 | 6 |
| cancelledAttempt|siblings|4096|latency|median | 0.8922417166880139 | -569.3761157475715 | 0 |
| cancelledAttempt|siblings|64|allocation_bop|median | 1.0272402968995378 | 241.99781271975644 | 6 |
| cancelledAttempt|siblings|64|latency|median | 0.9157081915522362 | -383.2670025203515 | 1 |
| cancelledAttempt|sparse|4096|allocation_bop|median | 0.9929453094197207 | -27.993240751590065 | 0 |
| cancelledAttempt|sparse|4096|latency|median | 1.2275856389218185 | 1291.0761895401938 | 6 |
| cancelledAttempt|sparse|64|allocation_bop|median | 0.9879661060569924 | -47.99148915388605 | 0 |
| cancelledAttempt|sparse|64|latency|median | 1.2844695347787987 | 1437.776166979177 | 6 |
| repair|malformed|4096|allocation_bop|median | 0.9999999939551063 | -1.160622346674245e-06 | 2 |
| repair|malformed|4096|latency|median | 0.9995756912141481 | -0.03294190231153493 | 2 |
| repair|malformed|64|allocation_bop|median | 1.0000000045852406 | 8.803682760571974e-07 | 4 |
| repair|malformed|64|latency|median | 1.0002535300014683 | 0.019913103060538617 | 3 |
| repair|nested|4096|allocation_bop|median | 0.8621495323243784 | -118.00015878406543 | 0 |
| repair|nested|4096|latency|median | 0.8621481578040249 | -27.10951254206188 | 0 |
| repair|nested|64|allocation_bop|median | 0.8383838267180497 | -128.00019022906366 | 0 |
| repair|nested|64|latency|median | 0.8283189845920704 | -32.68895464479576 | 0 |
| repair|siblings|4096|allocation_bop|median | 0.8260875750119254 | -127.99974219810906 | 0 |
| repair|siblings|4096|latency|median | 1.1870232567790624 | 36.67459260989074 | 6 |
| repair|siblings|64|allocation_bop|median | 0.8260874997720338 | -127.99979187274158 | 0 |
| repair|siblings|64|latency|median | 1.167375514198326 | 31.523639437634245 | 4 |
| repair|sparse|4096|allocation_bop|median | 0.41531317030323645 | -26636.035972317288 | 0 |
| repair|sparse|4096|latency|median | 0.49494085955504835 | -6065.83093648691 | 0 |
| repair|sparse|64|allocation_bop|median | 0.4776341268151407 | -5652.007006636106 | 0 |
| repair|sparse|64|latency|median | 0.5586744149256532 | -1180.737800148368 | 0 |
| visibleQuery|malformed|4096|allocation_bop|median | 1.0786927590002582 | 9.932294829420032e-07 | 6 |
| visibleQuery|malformed|4096|latency|median | 1.0791639275562501 | 0.17070417193794918 | 6 |
| visibleQuery|malformed|64|allocation_bop|median | 1.0692444819404683 | 8.756363057075304e-07 | 6 |
| visibleQuery|malformed|64|latency|median | 1.0692172951855035 | 0.14927515306341732 | 6 |
| visibleQuery|nested|4096|allocation_bop|median | 0.9999998783986819 | -4.864586859554265e-06 | 3 |
| visibleQuery|nested|4096|latency|median | 1.0000309513307162 | 0.02321038555561472 | 3 |
| visibleQuery|nested|64|allocation_bop|median | 1.000356351088402 | 2.06268207598591e-07 | 3 |
| visibleQuery|nested|64|latency|median | 0.9994103281945224 | -0.05834221126972494 | 3 |
| visibleQuery|siblings|4096|allocation_bop|median | 0.9977393229831463 | -1.8416668927216402e-06 | 1 |
| visibleQuery|siblings|4096|latency|median | 0.9992881449147204 | -0.09902794207872034 | 2 |
| visibleQuery|siblings|64|allocation_bop|median | 1.0014886279056436 | 8.615899763913127e-07 | 5 |
| visibleQuery|siblings|64|latency|median | 1.002099374617119 | 0.20746984074320807 | 5 |
| visibleQuery|sparse|4096|allocation_bop|median | 1.0102689087729009 | 4.601203484918591e-07 | 6 |
| visibleQuery|sparse|4096|latency|median | 1.0115494795954416 | 0.08809926330817319 | 6 |
| visibleQuery|sparse|64|allocation_bop|median | 1.0200024578281148 | 8.879893503195715e-07 | 6 |
| visibleQuery|sparse|64|latency|median | 1.0185725861208095 | 0.14090445155340214 | 6 |

Observed increases: 60 metrics; >20% investigation: 7 metrics.

See report.json for all six ratios, absolute deltas, raw JMH forks, per-run SDK metric values, null counts, overlap/outcome counts and exact input provenance.

Cancellation checkpoints differ by architecture. Edit restoration is a headless SDK observation upper bound with unknown worker origin; refusal censoring is not zero latency. Weak-GC deadline results do not establish total retained heap or a structural leak.
