-- simplified view for testing pat person hourly rate
CREATE VIEW FV_NAME_CONCATENATION AS
SELECT pn.name_id, c.case_id, c.case_role_sequence, c.role_type_id
FROM casting c, PAT_NAMES_ENTITY pn
WHERE c.actor_id = pn.name_id;
