SELECT u.id, r.x FROM users u CROSS JOIN LATERAL (SELECT u.id AS x) AS r;
