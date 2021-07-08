-- A schema dump of selected tables from Patricia v5.6.3, taken on 12nd December 2018
CREATE VIEW fv_case_price_list AS
SELECT fv.case_id , fv.case_category_id , fv.case_category_level , cpl.work_code_id , cpl.status_id , cpl.actor_id ,
    cpl.price_list_id , cpl.price_large_entity_flag , cpl.currency_id , MAX(cpl.price_change_date) AS price_change_date ,
    cpl.designated_state_id , cpl.PRICE_CHARGEABLE
FROM chargeing_price_list cpl , fv_case_case_category fv
WHERE fv.case_category_id = cpl.case_category_id AND cpl.validate_from_diary IS NULL
AND cpl.price_change_date <= GETDATE()
GROUP BY fv.case_id ,fv.case_category_id ,fv.case_category_level ,cpl.work_code_id ,
         cpl.status_id ,cpl.actor_id ,cpl.price_list_id ,cpl.price_large_entity_flag ,cpl.currency_id ,
         cpl.designated_state_id ,cpl.PRICE_CHARGEABLE;
