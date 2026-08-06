-- RigelMCMod schema v9: restart-based /wipeflatlands support.
--
-- A restart-based wipe deletes the world folder on the *next boot*, before any world is
-- loaded - the only way to guarantee the delete never races a still-held file handle from
-- the just-exited process (the in-place delete-while-running path has no such guarantee on
-- every OS/filesystem). This flag survives the restart in the database (a separate file
-- from the world folder itself, so it's never touched by the wipe).
ALTER TABLE rigel_world_state ADD COLUMN pending_wipe INTEGER NOT NULL DEFAULT 0;
