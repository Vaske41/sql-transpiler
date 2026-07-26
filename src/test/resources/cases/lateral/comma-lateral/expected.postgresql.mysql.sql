SELECT u.id, r.x FROM users AS u CROSS JOIN LATERAL (SELECT u.id AS x) AS r;
