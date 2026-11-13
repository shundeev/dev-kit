export type Board = { title: string; url: string }

declare module 'claude-code' {
  interface PluginState {
    'project-link': { board: Board | null }
  }
}
