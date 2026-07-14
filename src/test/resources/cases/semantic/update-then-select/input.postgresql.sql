DROP TABLE IF EXISTS stock;
CREATE TABLE stock (
  id INT NOT NULL PRIMARY KEY,
  qty INT NOT NULL
);
INSERT INTO stock (id, qty) VALUES (1, 10), (2, 20), (3, 30);
UPDATE stock SET qty = qty - 5 WHERE id = 2;
SELECT id, qty FROM stock ORDER BY id;
