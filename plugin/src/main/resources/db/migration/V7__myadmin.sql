-- A staff member's own personal join flavor line, set via /myadmin setlogin - see
-- myadmin.LoginMessageDao. TFM ref: Admin.loginMessage / Command_myadmin's
-- setlogin/clearlogin, studied directly - this project's /myadmin deliberately doesn't
-- port TFM's IP-list self-management subcommands (clearip/clearips): rigel_ip_history is
-- the actual anti-ban-evasion mechanism here (see its own javadoc), not just an admin-
-- panel convenience list like TFM's Admin.ips is, so letting a player self-service-erase
-- entries from it would be a real security regression, not a faithful port.
CREATE TABLE rigel_login_messages (
    uuid    VARCHAR(36) NOT NULL PRIMARY KEY,
    message TEXT        NOT NULL,
    set_at  BIGINT      NOT NULL
);
