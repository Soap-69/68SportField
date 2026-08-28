CREATE TABLE admin_user_audit (
    id                    BIGSERIAL     PRIMARY KEY,
    target_admin_user_id  BIGINT        NOT NULL REFERENCES admin_users(id),
    actor_admin_user_id   BIGINT        NOT NULL REFERENCES admin_users(id),
    action                VARCHAR(30)   NOT NULL,
    old_value             VARCHAR(100),
    new_value             VARCHAR(100),
    created_at            TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_user_audit_target ON admin_user_audit(target_admin_user_id);
CREATE INDEX idx_admin_user_audit_actor  ON admin_user_audit(actor_admin_user_id);
