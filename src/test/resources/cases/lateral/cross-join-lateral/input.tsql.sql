SELECT u.id, r.x FROM users u CROSS APPLY (SELECT u.id AS x) AS r;
