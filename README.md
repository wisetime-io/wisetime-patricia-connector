# WiseTime Patricia Connector

## Status

We are currently in the process of open sourcing the WiseTime Patricia Connector. This notice will be removed once we have pushed everything to this repository.

## About

The WiseTime Patricia Connector connects [WiseTime](https://wisetime.io) to [Patricia](https://www.patrix.com/patricia), and will automatically:

* Create a new WiseTime tag whenever a new Patricia case is created;
* Register worked time whenever a user posts time to WiseTime;
* Update budget whenever a user posts time to WiseTime.

In order to use the WiseTime Patricia Connector, you will need a [WiseTime Connect](https://wisetime.io/docs/connect/) API key. The WiseTime Patricia Connector runs as a Docker container and is easy to set up and operate.

## Configuration

Configuration is done through environment variables. The following configuration options are required.

| Environment Variable                      | Description                                                              |
| ----------------------------------------  | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| API_KEY                                   | Your WiseTime Connect API Key                                                                                                                                                                 |
| PATRICIA_JDBC_URL                         | The URL of the MSSQL DB that Patricia is using                                                                                                                                                |
| PATRICIA_JDBC_USERNAME                    | The username of the Patricia DB                                                                                                                                                               |
| PATRICIA_JDBC_PASSWORD                    | The password of the Patricia DB                                                                                                                                                               |
| PATRICIA_ROLE_TYPE_ID                     | The role type id from patricia database                                                                                                                                                       |
| PATRICIA_CASE_URL_PREFIX                  | If set, the connector will generate a URL when creating each tag. If a tag URL is available, the tag will be a clickable link in the WiseTime console. Clicking on the tag will open the URL. |


The following configuration options are optional.

| Environment Variable                 | Description                                                                                                                                                                                                                   |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| WORK_CODES_ZERO_CHARGE               | List of comma separated work codes, that will get assigned 0 chargeable time on each time group posted with them.                                                                                                             |
| CALLER_KEY                           | The caller key that WiseTime should provide with post time webhook calls. The connector does not authenticate Webhook calls if not set.                                                                                       |
| TAG_UPSERT_PATH                      | The tag folder path to use during creating Wisetime tags. Defaults to `/Patricia/` (trailing slash required). Use `/` for root folder.                                                                                        |
| TAG_UPSERT_BATCH_SIZE                | Number of tags to upsert at a time. A large batch size mitigates API call latency. Defaults to 500.                                                                                                                           |
| DATA_DIR                             | If set, the connector will use the directory as the location for storing data to keep track on the Patricia cases it has synced. By default, WiseTime Connector will create a temporary dir under `/tmp` as its data storage. |
| TIMEZONE                             | The timezone to use when posting time to Patricia, e.g. `Australia/Perth`. Defaults to `UTC`.                                                                                                                                 |
| RECEIVE_POSTED_TIME                  | If unset, this defaults to `LONG_POLL`: use long polling to fetch posted time. Optional parameters are `WEBHOOK` to start up a server to listen for posted time. `DISABLED` no handling for posted time                       |
| TAG_SCAN                             | If unset, this defaults to `ENABLED`: Set mode for scanning external system for tags and uploading to WiseTime. Possible values: ENABLED, DISABLED.                                                                           |
| WEBHOOK_PORT                         | The connector will listen to this port e.g. 8090, if RECEIVE_POSTED_TIME is set to `WEBHOOK`. Defaults to 8080.                                                                                                               |                                                                                                                    
| LOG_LEVEL                            | Define log level. Available values are: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` and `OFF`. Default is `INFO`.                                                                                                               |
| ADD_SUMMARY_TO_NARRATIVE             | When `true`, adds total worked time, total chargeable time and experience weighting (if less than 100%) to the narrative when posting time to Patricia. Defaults to `false`.                                                  |
| WT_CHARGE_TYPE_ID                    | Defines the Patricia charging type ID that WiseTime entries will be written with to Patricia. Default is `NULL`                                                                                                               |
| USE_SYSDEFAULT_CURRENCY_FOR_POSTING  | When `true` the connector will use the the system default currency, instead of determining it by case. Defaults to `false`.                                                                                                   |
| FALLBACK_CURRENCY                    | The currency to use when all other methods of currency resolution fail. Defaults to `NULL`, which means that an error will be thrown when no currency can be determined.                                                      |
| PATRICIA_LANGUAGE                    | The localization language of the connector that can be used for defining locale-dependant labels (e.g. work code text). It's the standard English name of the language (eg. `German`, `French`, etc.) Defaults to `English`.  |


## Running the WiseTime Postgres Connector

The easiest way to run the Postgres Connector is using Docker. For example:

```text
docker run -d \
    --restart=unless-stopped \
    -v volume_name:/usr/local/wisetime-connector/data \
    -e DATA_DIR=/usr/local/wisetime-connector/data \
    -e API_KEY=YourWisetimeApiKey \
    -e PATRICIA_JDBC_URL="YourPaticiaDatabaseUrl" \
    -e PATRICIA_JDBC_USERNAME=YourPatriciaDbUsername \
    -e PATRICIA_JDBC_PASSWORD=YourPatriciaDbPassword \
    -e PATRICIA_ROLE_TYPE_ID=YourPatriciaRoleTypeId \
    -e PATRICIA_CASE_URL_PREFIX="http://edms.company.com:9080/casebrowser/#/case/" \
    wisetime/patricia-connector
```

If you are using `RECEIVE_POSTED_TIME=WEBHOOK`: Note that you need to define port forwarding in the docker run command (and similarly any docker-compose.yaml definition). If you set the webhook port other than default (8080) you must also add the WEBHOOK_PORT environment variable to match the docker ports definition.

The Patricia connector runs self-checks to determine whether it is healthy. If health check fails, the connector will shutdown. This gives us a chance to automatically re-initialise the application through the Docker restart policy.
 
## Patricia Role Type ID
To know the correct role type id to use, run this query on Patricia DB:  
  ```sql
  SELECT ROLE_TYPE_ID FROM CASE_ROLE_TYPE WHERE ROLE_TYPE_LABEL = 'Account Address';
  ```

## Building

To build a Docker image of the WiseTime Patricia Connector, run:

```text
make docker
```
