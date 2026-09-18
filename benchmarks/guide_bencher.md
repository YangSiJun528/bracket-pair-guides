# Enable Bencher performance checks

Use the existing `Benchmark Jobs` workflow to measure the seven prepared JMH
jobs on Bencher Bare Metal and compare performance across commits and PRs.
The account connection and first remote run must be completed before treating
the integration as verified.

## Prerequisites

- A Bencher **Free** account and a **public** project.
- Permission to set this repository's Actions variables and secrets.
- For local checks: JDK 17, Docker, `jq`, Python 3, and Bencher CLI 0.6.12.

The [Free plan](https://bencher.dev/pricing/) allows 65,535 metrics per day,
one concurrent bare-metal job, and five minutes per job. No paid plan is needed.
The workflow runs the seven jobs sequentially with a 300-second remote limit
and a 240-second Java limit; compilation happens beforehand on GitHub Actions.

## Connect the repository

1. Create or select a public project in your Free Bencher account and create a
   project-scoped API key (`bencher_run_...`). Account-wide user keys are not
   supported by this workflow's registry login.
2. Open **Settings → Secrets and variables → Actions** in this GitHub repository.
   Set repository secret `BENCHER_API_KEY` to the API key. The workflow discovers
   its project through Bencher's authenticated API. Optionally set repository
   variable `BENCHER_PROJECT` to the slug to require an explicit match. See the
   [Bencher GitHub Actions guide](https://bencher.dev/docs/how-to/github-actions/).
3. After the workflow change is merged, check the first **Benchmark Jobs** run
   on `main` to establish the initial baseline. Use **Actions → Benchmark Jobs →
   Run workflow** on `main` if another run is needed. Subsequent matching pushes
   to `main` and eligible PR updates run automatically.
4. Check all seven reports and the coverage check before relying on alerts.
   Leave the Bencher checks optional until enough runs establish stable results.

Setting `BENCHER_API_KEY` switches same-repository PRs, main pushes, and manual
runs to Bencher. Project discovery rejects private projects, mismatched slugs,
and invalid keys before building bundles. Without connection settings, and for
fork or Dependabot PRs, the existing GitHub runner matrix validates execution and
coverage without uploading results. Setting a project variable without its API
key fails instead of silently using the fallback.
Draft PRs and PRs labeled `skip-ci` retain the workflow's existing skip behavior.

PR measurements use the actual head commit and request the PR's base
branch/commit as their start point. If that commit has no recorded results,
Bencher falls back to the latest recorded version of the base branch. Only the
first suite resets that start point, so later suites retain reports already
collected for the PR. The initial threshold flags latency
more than 20% above the latest historical result for each benchmark, using one
previous sample. This is a starting threshold to tune after observing variation.
Suite checks and PR comments report alerts; a performance alert does not prevent
the remaining suites from running.

## Validate the image and result adapter locally

Prepare every full-length bundle before building the image from the repository
root. Keep the existing forks, iterations, and parameters:

```shell
for job in $(./gradlew -q :benchmarks:listBenchmarkJobs | jq -r '.[]'); do
  ./gradlew :benchmarks:prepareBenchmarkJob -PbenchmarkJob="$job"
done
docker build --platform linux/amd64 -f benchmarks/bencher/Dockerfile \
  -t bracket-pair-guides-benchmarks .
docker run --rm --platform linux/amd64 --network none -e BENCHMARK_JOB=preferences \
  bracket-pair-guides-benchmarks
```

The image includes JDK 17, Python, and prebuilt bundles; its measurement command
needs no network. Linux AMD64 emulation on an ARM Mac can exceed the local time
limit. Use this run to validate packaging, not as the remote performance baseline.
See [Bencher image requirements](https://bencher.dev/docs/explanation/images/).

Prepare all 46 parameterized cases and check the CLI report payload without
uploading:

```shell
./gradlew :benchmarks:jmh --rerun -PbenchmarkSmoke=true
python3 benchmarks/bencher/normalize_results.py \
  benchmarks/build/reports/jmh/results.json \
  benchmarks/build/reports/jmh/bencher-results.json
bencher run --dry-run --adapter java_jmh \
  --file benchmarks/build/reports/jmh/bencher-results.json
```

The CLI dry run reads the result file but does not run Bencher's server-side
adapter. Verify the parsed cases in the first real Bencher reports.

The [Java JMH adapter](https://bencher.dev/docs/explanation/adapters/#-java-jmh)
identifies a benchmark by its method name. The normalizer appends sorted JMH
parameters to keep all 46 cases distinct and retains `originalBenchmark` for
restoring raw results. Smoke timings only check the payload structure. Bencher tracks
the primary latency; GC secondary metrics remain available in the raw artifacts.

## Inspect failures and control the queue

Download the workflow artifacts within three days. Each completed suite keeps
its Bencher report, remote job response, restored JMH JSON, and readable JMH log.
The existing coverage check verifies all 46 cases exactly once and confirms the
full measurement profile.

Bencher measurement jobs share one concurrency group and queue up to 100 pending
jobs with `queue: max`; new runs do not replace pending measurements. Preparation
can run concurrently across PRs. Workflow runs do not automatically cancel an
active run. See
[GitHub concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
If cancelling a GitHub run manually, wait until its remote
job is terminal in Bencher before starting another: cancelling the client does
not cancel an already submitted remote job. Remove `BENCHER_API_KEY` and the
optional `BENCHER_PROJECT` variable to return future runs to the standard GitHub
validation matrix.
