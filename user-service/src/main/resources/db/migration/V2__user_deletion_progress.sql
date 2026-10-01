ALTER TABLE users ADD COLUMN deletion_stage VARCHAR(32);

CREATE INDEX users_deletion_stage_idx ON users (deletion_stage)
    WHERE deletion_stage IS NOT NULL;

CREATE TABLE user_lifecycle_lock (id INTEGER PRIMARY KEY);
INSERT INTO user_lifecycle_lock(id) VALUES (1);
