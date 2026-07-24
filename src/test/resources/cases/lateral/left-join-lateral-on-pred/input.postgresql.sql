SELECT u.id, r.x FROM users u LEFT JOIN LATERAL (SELECT 1 AS x) AS r ON u.id = r.x;
