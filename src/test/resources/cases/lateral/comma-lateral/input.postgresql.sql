SELECT u.id, r.x FROM users u, LATERAL (SELECT u.id AS x) AS r;
