// Shared client state and the game registry — the foundation every other module builds on. No imports of its own, so it
// sits at the root of the dependency graph.
//
// `session` is the single source of truth for the signed-in player, their sockets, and the room they're currently in.
// `GAMES` is the per-game-type registry the rest of the client stays agnostic to: each entry carries display metadata
// plus, for games with a bespoke board view, a `render` function that the game's own module installs on load (see
// games/*.js and board.js's dispatch).

// The token is persisted to sessionStorage so a reload within the same tab stays signed in (and "My sessions" survives
// a refresh). sessionStorage is per-tab, so separate tabs/windows remain independent sessions.
export const session = {
  token: null,
  me: null, // { id, name }
  ws: null,
  traceWs: null,
  game: null // { roomId, gameType, isHost }
};

// Per-game-type knowledge the rest of the client stays agnostic to: display label, how a click becomes a move, and
// whether moves are made by picking a column (Connect Four) rather than an individual cell. Games with a custom board
// view attach a `render(state)` here from their own module when it loads; grid games (Tic-Tac-Toe, Connect Four) have
// none and fall through to board.js's shared grid renderer.
export const GAMES = {
  tictactoe:   { label: "Tic-Tac-Toe",    maxPlayers: 2, move: (row, col) => ({ row, col }) },
  connectfour: { label: "Connect Four",   maxPlayers: 2, move: (row, col) => ({ col }), columns: true },
  battleship:  { label: "Battleship",     maxPlayers: 2, move: (row, col) => ({ row, col }) },
  pig:         { label: "Pig",            maxPlayers: 8, pig: true },
  mastermind:  { label: "Mastermind",     maxPlayers: 2, mastermind: true },
  liarsdice:   { label: "Liar's Dice",    maxPlayers: 6, liarsdice: true },
  texasholdem: { label: "Texas Hold 'Em", maxPlayers: 6, texasholdem: true }
};

// Terse element lookup by id, used pervasively across the UI modules.
export const $ = (id) => document.getElementById(id);
