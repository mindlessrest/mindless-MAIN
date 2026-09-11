Implement the Myau KillAura port into the existing Mindless Kill Aura module, using only the KillAura section of PredAC as the defaults. Complete all source settings and modes, not only the default Legit autoblock path.

Start by reading this execution handoff in full:

`C:/Users/Michael/Downloads/mindless-MAIN-master (1)/mindless-MAIN-master/client/docs/KILLAURA_PORT_HANDOFF.md`

Supporting files are in the same directory:

- `KILLAURA_PREDAC_DEFAULTS.json` is the exact 38-field source configuration fixture. It is not a loadable Mindless profile.
- `KILLAURA_PORT_SOURCE_MANIFEST.json` identifies the inspected source and destination versions. Re-read anything that changed. Do not overwrite newer work with an older baseline.
- `KILLAURA_IMPLEMENTATION_PLAN.md` is the short overview. The detailed handoff's explicit corrections take precedence.

The handoff contains the exact settings/profile-key table, migration recipe, target selection order, signed cooldown examples, packet acceptance rules, all nine autoblock modes, lifecycle cleanup requirements, host integration decisions, test cases, and build commands. Read the actual source methods for the stage you are implementing. Do not substitute a generic KillAura implementation or assume similarly named host utilities behave identically.

Work from the client directory. Read AGENTS.md, preserve existing work, and establish a baseline before edits. Follow the handoff's stages 0 through 7. Keep a concise `docs/KILLAURA_PORT_PROGRESS.md` with completed stages, evidence, intentional behavior differences, and the exact next unfinished step. Reopen the relevant handoff section after context compaction.

Pay particular attention to accepted C03 resetting packet state, negative cooldown remainders, pre-decrement autoblock suppression, final rotation ownership, buffered sends being represented as cancellation in the current host, and exactly-once execution across runtime hooks and Timer subupdates. These are behavioral requirements, not optional cleanup.

Keep the existing module identity and Mindless's event, profile, rotation, and packet-delay systems. Preserve the caller integrations listed in the handoff. Do not add another registered aura, a second packet queue, unsupported-mode placeholders, or a global defaults overwrite. Do not import other PredAC modules.

Proceed with the authorized implementation without asking permission for routine edits or tests. Resolve ordinary implementation choices using the handoff and code evidence. Record source/host conflicts and the tested resolution instead of silently guessing. Do not claim live validation unless the actual client was exercised. Finish with the changes made, tests actually run, built artifact paths, and any explicitly unverified behavior.
