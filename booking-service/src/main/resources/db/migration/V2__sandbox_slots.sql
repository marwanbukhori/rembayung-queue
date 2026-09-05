-- Marks a slot as belonging to a throwaway console sandbox, and says when it
-- may be reaped.
--
-- One nullable timestamp rather than a boolean plus a date, because the two
-- facts are the same fact: NULL means a real slot that lives forever, and a
-- value means a sandbox that expires at that moment. A boolean would allow
-- "sandbox but no expiry", which is the row that never gets cleaned up.
--
-- Console sessions are deliberately unlimited, so without this every abandoned
-- demo would sit in the table forever and contribute four Prometheus gauge
-- series each.
ALTER TABLE slots ADD (sandbox_expires_at TIMESTAMP NULL);

-- Oracle does not index NULLs in a single-column B-tree, so this index contains
-- exactly the sandbox rows and nothing else — which is precisely the set the
-- sweeper scans.
CREATE INDEX ix_slots_sandbox_expiry ON slots (sandbox_expires_at);
