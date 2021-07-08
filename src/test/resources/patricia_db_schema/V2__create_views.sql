-- A schema dump of selected tables from Patricia v5.6.3, taken on 12nd December 2018
CREATE VIEW FV_CASE_CASE_CATEGORY AS
SELECT me.case_id, cc.case_category_id, cc.case_category_level
FROM (SELECT pc.case_id, ctd.case_master_id, pc.case_type_id, pc.state_id,
         (SELECT def_state_id FROM case_type_default_state def WHERE def.case_type_id = ctd.case_master_id AND def.state_id = pc.state_id) AS def_state_id,
         pc.application_type_id, pc.service_level_id
      FROM pat_case pc, case_type_definition ctd
      WHERE  ctd.case_type_id = pc.case_type_id) me, case_category cc
      WHERE  ( cc.case_master_id = me.case_master_id OR cc.case_master_id IS NULL )
      AND ( cc.case_type_id = me.case_type_id OR cc.case_type_id IS NULL )
      AND ( cc.state_id = me.state_id OR cc.state_id = me.def_state_id OR cc.state_id IS NULL )
      AND ( cc.application_type_id = me.application_type_id OR cc.application_type_id IS NULL )
      AND ( cc.service_level_id = me.service_level_id OR cc.service_level_id IS NULL );
