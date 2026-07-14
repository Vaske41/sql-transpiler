DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS users;
CREATE TABLE users (
  id INT NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL
);
CREATE TABLE orders (
  id INT NOT NULL PRIMARY KEY,
  user_id INT NOT NULL
);
INSERT INTO users (id, name) VALUES (1, 'Ann'), (2, 'Bob'), (3, 'Cid');
INSERT INTO orders (id, user_id) VALUES (10, 1), (11, 1), (12, 2);
SELECT id, name FROM users
WHERE id IN (SELECT user_id FROM orders)
ORDER BY id;
