import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

import type { Board } from '../types'

// The project's board link on the prompt footer, next to the permission mode and the PR (dev-kit#30).
// The board comes from kit (dev-kit#139): `kit board show` prints it as JSON, from the folder's kit.edn,
// then the environment. Without kit or a board there is no link.

const board = atom({ plugin: 'project-link', key: 'board' } as const, null)

async function boardRef($: EngineInterface): Promise<{ owner: string; number: string } | null> {
  try {
    const { exitCode, stdout } = await $.process.run(['.shared/bin/kit', 'board', 'show'], { timeoutMs: 15000 })
    if (exitCode === 0) {
      const ref = JSON.parse(stdout)
      if (ref?.owner && ref?.number) {
        return { owner: String(ref.owner), number: String(ref.number) }
      }
    }
  } catch {
    // kit missing, not JSON, or slow: no board
  }
  return null
}

async function load($: EngineInterface): Promise<void> {
  const ref = await boardRef($)
  if (ref === null) {
    return
  }
  let found: Board = {
    title: `#${ref.number}`,
    url: `https://github.com/users/${ref.owner}/projects/${ref.number}`,
  }
  try {
    const { exitCode, stdout } = await $.process.run(
      ['gh', 'project', 'view', ref.number, '--owner', ref.owner, '--format', 'json'],
      { timeoutMs: 15000 },
    )
    if (exitCode === 0) {
      const p = JSON.parse(stdout)
      found = { title: p.title ?? found.title, url: p.url ?? found.url }
    }
  } catch {
    // gh missing or offline: keep the guessed title and URL
  }
  await update($, board, () => found)
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    const result = await next(e)
    await load($)
    return result
  })

  // The mode labels on the footer line (the one with the permission mode and the PR):
  // the link goes there, ahead of any labels the engine has, joined the engine's way.
  on('ui.render', { component: 'SessionMode' }, async ($, e, next) => {
    const b = await read($, board)
    if (b === null) {
      return next(e)
    }

    const { Box, Link, Text } = $.ui.resolve(e)
    const modes = e.props.modes

    return (
      <Box>
        <Link href={b.url}>Проект "{b.title}"</Link>
        {modes.length > 0 ? <Text dimColor>{' · ' + modes.join(' & ')}</Text> : null}
      </Box>
    )
  })
}
