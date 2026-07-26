UPDATE superhero AS s, team_member_superhero AS tms INNER JOIN team_member AS tm ON tms.team_member_id = tm.id SET s.full_name = 'Superman' WHERE s.id = tms.superhero_id AND tm.team_id = 91;
