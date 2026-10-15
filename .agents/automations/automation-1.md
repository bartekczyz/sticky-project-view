<!--
An automation is a prompt for an agent. Air sends it to Junie after live events in Agent Sessions,
and Junie can give the work to another agent in a new session.

These events start an automation: the IDE starts, a session starts its turn, a session ends its
turn, a session waits for input. Air groups the events of 10 seconds into one run, and it names the
sessions that caused the run and the files they changed.

Write the prompt below this comment. Make the first line a short title. Then write what must happen,
and when. Air enables the automation when you save this file.

Three examples:

Review a public API change
When a session ends its turn and it changed a file of the API module, ask Codex to review the
change. Ask it to name every caller it breaks, and to report only a concrete risk.

Update the main branch
When the IDE starts, and the repository is on the main branch, and the working tree is clean, ask
Junie to run a fast-forward pull. Do nothing when the working tree holds a change.

Note a session that waits
When a session waits for input two times in one hour, write one short note. Name the session and the
question it asks.
-->

