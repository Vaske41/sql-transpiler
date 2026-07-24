SELECT u.id, r.x FROM users AS u, LATERAL (SELECT u.id AS x) AS r;
