const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const {test} = require('node:test')

const repository = path.resolve(__dirname, '../..')
const workflow = fs.readFileSync(
  path.join(repository, '.github/workflows/visual-test-report.yml'),
  'utf8',
)
const context = {
  repo: {owner: 'YangSiJun528', repo: 'bracket-pair-guides'},
  payload: {
    workflow_run: {
      id: 37271099549,
      run_attempt: 2,
      conclusion: 'success',
      event: 'pull_request',
      name: 'Visual Test Scenarios v2',
      path: '.github/workflows/visual-test.yml',
      head_repository: {full_name: 'YangSiJun528/bracket-pair-guides'},
      head_sha: '6db96245e0e4d7a94a3537f06d38e87fef584708',
      pull_requests: [{number: 88, head: {sha: '6db96245e0e4d7a94a3537f06d38e87fef584708'}}],
    },
  },
}

// Execute the committed inline scripts without adding a YAML runtime dependency
// or checking out pull-request code in the privileged reporter.
function scriptFor(stepName) {
  const lines = workflow.split('\n')
  const step = lines.indexOf(`      - name: ${stepName}`)
  assert.ok(step >= 0, `Missing workflow step: ${stepName}`)
  let end = step + 1
  while (end < lines.length && !lines[end].startsWith('      - name: ')) end += 1
  const script = lines.indexOf('          script: |', step)
  assert.ok(script > step && script < end, `Missing inline script: ${stepName}`)
  const source = lines.slice(script + 1, end).map(line => {
    assert.ok(line === '' || line.startsWith('            '))
    return line.slice(12)
  }).join('\n')
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor
  return new AsyncFunction('github', 'context', 'core', 'require', 'fetch', 'process', source)
}

function image(name, id) {
  const baseline = path.join(
    repository,
    'plugin/src/visualTest/resources/baselines',
    'ideaIC-2024.2.6/linux-x64-xvfb96-darcula-scale1',
    name.replace('-actual.png', '.png'),
  )
  const contents = fs.readFileSync(baseline)
  return {
    id,
    name,
    size: contents.length,
    digest: `sha256:${crypto.createHash('sha256').update(contents).digest('hex')}`,
    contents,
  }
}

const images = [
  image('pair-border-only-actual.png', 11328219940),
  image('default-palette-actual.png', 11328642866),
]

async function download(selected, responseFor, inspect = () => {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visual-report-download-'))
  const requested = []
  try {
    const github = {rest: {actions: {
      downloadArtifact: async options => {
        requested.push(options.artifact_id)
        assert.equal(options.archive_format, 'zip')
        assert.equal(options.request.redirect, 'manual')
        return {status: 302, headers: {location: `https://artifacts.example/${options.artifact_id}`}}
      },
    }}}
    await scriptFor('Download Captures Without Extraction')(
      github, context, {info() {}}, require,
      async url => responseFor(Number(url.pathname.slice(1))),
      {env: {
        IMAGE_ARTIFACTS: JSON.stringify(selected),
        IMAGES_DIRECTORY: directory,
      }},
    )
    inspect(directory, requested)
  } finally {
    fs.rmSync(directory, {recursive: true, force: true})
  }
}

test('downloads current-attempt images even when older same-name artifacts have larger IDs', async () => {
  const listJobs = () => {}
  const listArtifacts = () => {}
  const oldIds = [11328607440, 11328690670]
  const artifacts = images.flatMap((image, index) => [
    {
      ...image, id: oldIds[index], size_in_bytes: image.size,
      created_at: '2026-10-05T06:15:18Z', expired: false,
    },
    {
      ...image, size_in_bytes: image.size,
      created_at: '2026-10-05T06:21:44Z', expired: false,
    },
  ])
  const outputs = {}
  const github = {
    rest: {
      pulls: {get: async () => ({data: {
        state: 'open', draft: false, labels: [], base: {ref: 'main'},
        head: {
          sha: context.payload.workflow_run.head_sha,
          repo: {full_name: 'YangSiJun528/bracket-pair-guides'},
        },
      }})},
      actions: {
        listJobsForWorkflowRunAttempt: listJobs,
        listWorkflowRunArtifacts: listArtifacts,
      },
    },
    paginate: async (operation, options) => {
      assert.equal(options.run_id, context.payload.workflow_run.id)
      if (operation === listJobs) {
        assert.equal(options.attempt_number, 2)
        return [{
          name: 'Visual Test', conclusion: 'success',
          started_at: '2026-10-05T06:17:16Z', completed_at: '2026-10-05T06:22:00Z',
        }]
      }
      assert.equal(operation, listArtifacts)
      return artifacts
    },
  }
  await scriptFor('Inspect Allowlisted Artifacts')(
    github, context, {setOutput: (key, value) => { outputs[key] = value }}, require,
  )
  assert.equal(outputs.should_report, 'true')
  assert.equal(outputs.image_ids, images.map(image => image.id).join(','))
  await download(
    JSON.parse(outputs.image_artifacts),
    id => new Response(images.find(image => image.id === id).contents),
    (directory, requested) => {
      assert.deepEqual(requested, images.map(image => image.id))
      assert.deepEqual(fs.readdirSync(directory).sort(), images.map(image => image.name).sort())
      for (const image of images) {
        assert.deepEqual(fs.readFileSync(path.join(directory, image.name)), image.contents)
      }
    },
  )
})

test('rejects a download with a mismatched digest', async () => {
  const corrupted = Buffer.from(images[0].contents)
  corrupted[0] ^= 0xff
  await assert.rejects(
    download([images[0]], () => new Response(corrupted)),
    /Artifact digest mismatch/,
  )
})

test('rejects a truncated download', async () => {
  await assert.rejects(
    download([images[0]], () => new Response(images[0].contents.subarray(1))),
    /Artifact size changed/,
  )
})

test('rejects a download that exceeds its validated size', async () => {
  await assert.rejects(
    download([images[0]], () => new Response(Buffer.concat([images[0].contents, Buffer.of(0)]))),
    /Artifact exceeded its validated size/,
  )
})

test('rejects an unsuccessful download response', async () => {
  await assert.rejects(
    download([images[0]], () => new Response(null, {status: 404})),
    /Artifact download failed/,
  )
})
