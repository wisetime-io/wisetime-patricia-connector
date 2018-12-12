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
	CASE_TYPE_ID int NOT NULL
);

CREATE TABLE PERSON (
	LOGIN_ID nvarchar(20) NOT NULL,
	EMAIL nvarchar(100) NULL
);

