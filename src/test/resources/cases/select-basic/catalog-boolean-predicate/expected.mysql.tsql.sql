CREATE TABLE users (id INT NOT NULL PRIMARY KEY, active BIT NOT NULL DEFAULT 1);
INSERT INTO users (id, active) VALUES (1, 1), (2, 0);
SELECT id FROM users WHERE active <> 0 ORDER BY id;
