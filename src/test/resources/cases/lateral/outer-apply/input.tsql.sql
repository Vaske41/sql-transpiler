SELECT u.id, r.x FROM users u OUTER APPLY (SELECT u.id AS x) AS r;
