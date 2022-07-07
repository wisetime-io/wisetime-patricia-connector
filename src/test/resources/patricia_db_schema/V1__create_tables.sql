-- A schema dump of selected tables from Patricia v5.6.3, taken on 12nd December 2018

CREATE TABLE VW_CASE_NUMBER (
	CASE_ID int NOT NULL,
	CASE_NUMBER nvarchar(40) NOT NULL
);

CREATE TABLE PAT_CASE (
	CASE_ID int NOT NULL,
	CASE_CATCH_WORD nvarchar(120) NULL,
	STATE_ID nvarchar(2) NOT NULL,
	APPLICATION_TYPE_ID int NOT NULL,
	SERVICE_LEVEL_ID int NULL,
	CASE_TYPE_ID int NOT NULL,
	STATUS_ID int NULL
);

CREATE TABLE PERSON (
	LOGIN_ID nvarchar(20) NOT NULL,
	EMAIL nvarchar(100) NULL,
	HOURLY_RATE real NULL,
);

CREATE TABLE CASTING (
	ROLE_TYPE_ID int NOT NULL,
	ACTOR_ID int NOT NULL,
	CASE_ID int NOT NULL,
	CASE_ROLE_SEQUENCE int
);

CREATE TABLE PAT_NAMES (
	NAME_ID int NOT NULL,
	price_list_id int null,
	CURRENCY_ID nvarchar(3) NULL
);

CREATE TABLE PAT_PERSON_HOURLY_RATE (
	PAT_PERSON_HOURLY_RATE_ID int NOT NULL,
	LOGIN_ID nvarchar(20) NOT NULL,
	WORK_CODE_ID nvarchar(10) NULL,
	HOURLY_RATE decimal(10, 2) NULL,
	NAME_ID int,
    name_role_type_id int,
    currency nvarchar(3)
);

CREATE TABLE PAT_WORK_CODE_DISCOUNT_HEADER (
	DISCOUNT_ID int NOT NULL,
	ACTOR_ID int NOT NULL,
	CASE_TYPE_ID int NULL,
	STATE_ID nvarchar(2) NULL,
	APPLICATION_TYPE_ID int NULL,
	WORK_CODE_TYPE nvarchar(1) NULL,
	WORK_CODE_ID nvarchar(20) NULL,
	DISCOUNT_TYPE int NOT NULL
);

CREATE TABLE PAT_WORK_CODE_DISCOUNT_DETAIL (
	DISCOUNT_ID int NOT NULL,
	AMOUNT decimal(14, 2) NOT NULL,
	DISCOUNT_PCT decimal(10, 6) NULL,
	price_change_formula nvarchar(255) NOT NULL
);

CREATE TABLE BUDGET_HEADER (
	CASE_ID int NOT NULL,
	BUDGET_EDIT_DATE datetime NULL
);

CREATE TABLE TIME_REGISTRATION (
	B_L_SEQ_NUMBER int NOT NULL,
	WORK_CODE_ID nvarchar(10) NOT NULL,
	CASE_ID int NOT NULL,
	REGISTRATION_DATE_TIME datetime NOT NULL,
	LOGIN_ID nvarchar(20) NOT NULL,
	CALENDAR_DATE datetime NULL,
	WORKED_TIME numeric(8, 2) NULL,
	DEBITED_TIME numeric(8, 2) NULL,
	TIME_TRANSFERRED nvarchar(1) NULL,
	NUMBER_OF_WORDS int NULL,
	WORKED_AMOUNT numeric(10, 2) NULL,
	B_L_CASE_ID int NULL,
	TIME_COMMENT_INVOICE ntext NULL,
	TIME_COMMENT ntext NULL,
	TIME_REG_BOOKED_DATE datetime NULL,
	EARLIEST_INVOICE_DATE datetime NULL
);

CREATE TABLE BUDGET_LINE (
	B_L_SEQ_NUMBER int NOT NULL,
	WORK_CODE_ID nvarchar(10) NOT NULL,
	B_L_QUANTITY numeric(8, 2) NULL,
	B_L_ORG_QUANTITY numeric(8, 2) NULL,
	B_L_UNIT_PRICE numeric(14, 2) NULL,
	B_L_ORG_UNIT_PRICE numeric(14, 2) NULL,
	B_L_UNIT_PRICE_NO_DISCOUNT numeric(14, 2) NULL,
	DEB_HANDLAGG nvarchar(20) NULL,
	B_L_AMOUNT numeric(14, 2) NULL,
	B_L_ORG_AMOUNT numeric(14, 2) NULL,
	CASE_ID int NOT NULL,
	SHOW_TIME_COMMENT int NULL,
	REGISTERED_BY nvarchar(20) NULL,
	EARLIEST_INV_DATE datetime NULL,
	B_L_COMMENT ntext NULL,
	RECORDED_DATE datetime NULL,
	DISCOUNT_PREC numeric(10, 6) NULL,
	DISCOUNT_AMOUNT numeric(14, 2) NULL,
	CURRENCY_ID nvarchar(3) NULL,
	EXCHANGE_RATE numeric(10, 6) NULL,
	INDICATOR nvarchar(3) NULL,
    P_L_ORG_UNIT_PRICE numeric(14,2),
    P_L_ORG_UNIT_PRICE_NO_DISCOUNT numeric(14,2),
    P_L_ORG_AMOUNT numeric(14,2),
    P_L_ORG_CURRENCY_ID nvarchar(3),
	EXTERNAL_INVOICE_DATE datetime null,
	-- added on October 18th 2019
	CHARGEING_TYPE_ID int null
);

-- Added on April 16th 2019
create table WORK_CODE
(
	WORK_CODE_ID nvarchar(10) not null
		constraint PK_WORK_CODE
			primary key,
    WORK_CODE_TYPE nvarchar(1) NULL,
	WORK_CODE_DEFAULT_AMOUNT numeric(10,2),
	REPLACE_AMOUNT int,
	IS_ACTIVE int NULL
);

CREATE TABLE CURRENCY  (
   CURRENCY_ID      nvarchar(3) PRIMARY KEY,
   CURRENCY_LABEL   nvarchar(40) NULL,
   CURRENCY_PREFIX  nvarchar(30) NULL,
   CURRENCY_SUFIX   nvarchar(30) NULL,
   default_currency int NULL,
   currency_active  int NULL
);

-- Added on June 15th 2020
create table CHARGEING_PRICE_LIST
(
	CASE_CATEGORY_ID int not null,
	STATUS_ID int not null,
	PRICE_LIST_DESIGNATED int,
	WORK_CODE_ID nvarchar(10) not null,
	PRICE_CHANGE_DATE datetime not null,
	ACTOR_ID int not null,
	PRICE_UNIT nvarchar(10),
	PRICE decimal(15,2),
	PRICE_FOR_AMOUNT numeric(10,2),
	PRICE_CHARGEABLE numeric(1),
	PRICE_TYPE nvarchar(1),
	PRICE_LIST_ID int not null,
	LOCAL_CURRENCY int,
	PRICE_LARGE_ENTITY_FLAG int,
	PRICE_LARGE_ENTITY numeric(10,2),
	CURRENCY_ID nvarchar(3),
	DEFAULT_PRICE_LIST int,
	DESIGNATED_STATE_ID nvarchar(2),
	MAINTAINED_BY_PATRIX int,
	VALIDATE_FROM_DIARY int,
	login_id nvarchar(20) not null,
	PRICE_MICRO_ENTITY_FLAG int,
	PRICE_MICRO_ENTITY numeric(10,2),
	INVENTOR_AMOUNT numeric(15,2),
	PERCENT_LOWER int,
	PERCENT_UPPER int
);

create table CASE_TYPE_DEFINITION
(
	CASE_TYPE_ID int not null,
	CASE_TYPE_CODE nvarchar(3),
	CASE_TYPE_LABEL nvarchar(30),
	CASE_TYP_VAT_ACCOUNT int,
	CASE_TYPE_NO_VAT_ACCOUNT int,
	CASE_TYPE_COST_ACCOUNT int,
	CASE_TYPE_DISB_ACCOUNT int,
	KSTNR int,
	CASE_TYPE_TO_MAIL_CHECK numeric(2),
	CASE_TYPE_CLASS_GROUP nvarchar(1),
	NEXT_NUMBER_OF_THE_TYPE int,
	CASE_MASTER_ID int,
	IS_ACTIVE int,
	NEXT_NUMBER_FOR_INVOICE_ID int,
	NEXT_NUMBER_FOR_OFFER_ID int,
	NEXT_NUMBER_FOR_ACCOUNT_BILLS int,
	NEXT_NUMBER_FOR_INVOICE_CREDIT int,
	NEXT_NUMBER_FOR_PURCHASE int
);

create table CASE_TYPE_DEFAULT_STATE
(
	CASE_TYPE_ID int,
	STATE_ID nvarchar(2),
	DEF_STATE_ID nvarchar(2),
	MAINTAINED_BY_PATRIX int,
	constraint PK_CASE_TYPE_DEFAULT_STATE
		primary key (CASE_TYPE_ID, STATE_ID)
);

create table DIARY_LINE
(
	FIELD_NUMBER int not null,
	CASE_ID int not null,
	RESYCLE_NO int,
	CASE_CATEGORY_ID int,
	PENDING_VALIDATION int,
	LOCKED_BY nvarchar(20),
	constraint PK_DIARY_LINE
		primary key (FIELD_NUMBER, CASE_ID)
);

create table DESIGNATED_STATES
(
	STATE_ID nvarchar(2) not null,
	CASE_ID int not null,
	ORG_STATE_ID nvarchar(2) not null,
	COPIED_CASE_EXISTS int,
	PARTIAL_OR_TOTAL_REFUSED int,
	APPLICATION_TYPE_ID int,
	AGENT_ID int,
	NO_CLASSES int,
	OFFICIAL_APPL_FEE numeric(10,3),
	OFFICIAL_REG_FEE numeric(10,3),
	AGENT_APPL_FEE numeric(10,3),
	AGENT_REG_FEE numeric(10,3),
	FEE_CURRENCY nvarchar(3),
	AMENDMENT_DATE datetime,
	REMARK nvarchar(max),
	NOT_RENEWED int,
	EXT_APPL_DATE datetime,
	EXT_REG_DATE datetime,
	EXTENDED int,
	VALIDATED_FLAG int,
	EXT_REG_NO nvarchar(60),
	classes nvarchar(2000),
	DURATION datetime,
	DETAIL_DATE datetime,
	DESIGNATED_SEQ int default 1 not null,
	GRANT_OF_PROTECTION datetime,
	PUBLICATION_DATE datetime,
	PUBLICATION_NUM nvarchar(60),
	USE_YES_NO int,
	FIRST_USE datetime,
	KEEP int,
	CLIENT_REFERENCE nvarchar(255),
	constraint PK_DESIGNATED_STATES
		primary key (STATE_ID, CASE_ID, ORG_STATE_ID, DESIGNATED_SEQ)
);

create table CASE_CATEGORY
(
	CASE_CATEGORY_ID int,
	CASE_TYPE_ID int,
	CASE_MASTER_ID int,
	STATE_ID nvarchar(2),
	APPLICATION_TYPE_ID int,
	SERVICE_LEVEL_ID int,
	CASE_CATEGORY_LEVEL int,
	CASE_SCRIPT_EXIST numeric(1),
	COPY_CASE_SCRIPT_EXIST numeric(1),
	APPLICATION_TYPE_SCRIPT_EXIST numeric(1),
	CASE_ROLE_SCRIPT_EXIST numeric(1),
	DIARY_MATRIX_EXIST numeric(1),
	RENEWAL_FEE_EXIST int,
	RENEWAL_SERVICE_CHARGE_EXIST int,
	CHARGEING_PRICELIST_EXIST int,
	RULE_EXIST int,
	CHARGEING_MATRIX_EXIST int,
	MAINTAINED_BY_PATRIX int,
	CASE_CATEGORY_CODE nvarchar(19),
	DATA_COMPARISON_MAPPING_EXIST int,
	COMPANY_ID int,
	SITE_ID int
);

create table RENEWAL_PRICE_LIST
(
	PRICE_LIST_ID int not null,
	PRICE_LIST_LABEL nvarchar(30),
	DEFAULT_PRICE_LIST int
);

-- Added on October 27th 2020
create table WORK_CODE_TEXT
(
    WORK_CODE_ID nvarchar(10) not null,
    WORK_CODE_TEXT nvarchar(60),
    LANGUAGE_ID int
);

create table LANGUAGE_CODE
(
    LANGUAGE_ID int not null,
    LANGUAGE_LABEL nvarchar(30)
);

create table PAT_NAMES_ENTITY
(
    ENTITY_ID              int           not null
        constraint PK_PAT_NAMES_ENTITY
            primary key,
    NAME_ID                int           not null,
);
