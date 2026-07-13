DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS users;
CREATE TABLE users (
  id INT NOT NULL PRIMARY KEY,
  name VARCHAR(32) NOT NULL
);
CREATE TABLE orders (
  id INT NOT NULL PRIMARY KEY,
  user_id INT NOT NULL,
  total DECIMAL(10,2) NOT NULL
);
INSERT INTO users (id, name) VALUES (1, 'Ann'), (2, 'Bob');
INSERT INTO orders (id, user_id, total) VALUES
  (10, 1, 20.00),
  (11, 2, 30.00),
  (12, 1, 5.00);
SELECT o.id, u.name, o.total
FROM orders o
INNER JOIN users u ON o.user_id = u.id
ORDER BY o.id;
