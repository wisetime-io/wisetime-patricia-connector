# WiseTime Patricia Connector

The WiseTime Jira Connector connects [WiseTime](https://wisetime.io) to [Patricia](https://www.patrix.com/patricia), and will automatically:

* Create a new WiseTime tag whenever a new Patricia case is created;
* Register worked time whenever a user posts time to WiseTime;
* Update budget whenever a user posts time to WiseTime.

In order to use the WiseTime Patricia Connector, you will need a [WiseTime Connect](https://wisetime.io/docs/connect/) API key. The WiseTime Patricia Connector runs as a Docker container and is easy to set up and operate.

## Configuration

Configuration is done through environment variables. The following configuration options are required.

| Environment Variable                      | Description                                                              |
| ----------------------------------------  | ------------------------------------------------------------------------ |
| API_KEY                                   | Your WiseTime Connect API Key                                            |
| PATRICIA_JDBC_URL                         | The URL of the MSSQL DB that Patricia is using                           |
| PATRICIA_JDBC_USERNAME                    | The username of the Patricia DB                                          |
| PATRICIA_JDBC_PASSWORD                    | The password of the Patricia DB                                          |
| PATRICIA_ROLE_TYPE_ID                     | The role type id from patricia database                                  |
| TAG_MODIFIER_PATRICIA_WORK_CODE_MAPPINGS  | Work code mappings with modifier name as key and work code id as value   |
| DEFAULT_WORK_CODE_NAME                    | The default work code to be used when modifier is not set to posted time.|


The following configuration options are optional.

| Environment Variable             | Description                                                                                                                             |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| CALLER_KEY                       | The caller key that WiseTime should provide with post time webhook calls. The connector does not authenticate Webhook calls if not set. |
| TAG_UPSERT_PATH                  | The tag folder path to use during creating Wisetime tags. Defaults to `/Patricia/` (trailing slash required). Use `/` for root folder.  |
| TAG_UPSERT_BATCH_SIZE            | Number of tags to upsert at a time. A large batch size mitigates API call latency. Defaults to 500.                                     |
| TIMEZONE                         | The timezone to use when posting time to Patricia, e.g. `Australia/Perth`. Defaults to `UTC`.                                           |
| PATRICIA_TIME_COMMENT_INVOICE    | Optional label to use in time charge/invoice record instead of narrative provided with posted time.                                     |
| INCLUDE_TIME_DURATION_TO_COMMENT | Set to `true` if app duration should be included in the time comment. Default value is `false`.                                         |


## Running the WiseTime Postgres Connector

The easiest way to run the Postgres Connector is using Docker. For example:

```text
docker run -d \
    -p 80:80 \
    --restart=unless-stopped \
    -e API_KEY=YourWisetimeApiKey \
    -e PATRICIA_JDBC_URL="YourPaticiaDatabaseUrl" \
    -e PATRICIA_JDBC_USERNAME=YourPatriciaDbUsername \
    -e PATRICIA_JDBC_PASSWORD=YourPatriciaDbPassword \
    -e PATRICIA_ROLE_TYPE_ID=YourPatriciaRoleTypeId \
    -e TAG_MODIFIER_PATRICIA_WORK_CODE_MAPPINGS=modifier_name_1:work_code_id_1,modifier_name_1:work_code_id_2 \
    -e DEFAULT_WORK_CODE_NAME=modifier_name_1 \
    wisetime/patricia-connector
```

The Patricia connector runs self-checks to determine whether it is healthy. If health check fails, the connector will shutdown. This gives us a chance to automatically re-initialise the application through the Docker restart policy.

## How to setup Modifier and Work Code ID mapping
To post time in Patricia, user should send time in WT Console with modifier. Each modifier is map to a Work Code ID in 
Patricia.

To add modifier, we need to:
1. In WT Console, add record to `team_modifiers` with user friendly modifier name (e.g. `Client Meeting`, `Research`).
2. In Patricia config file, update the `tag.modifier.patricia.work.code.mappings` key with the mappings in this format: 
`key1:val1,key2:val2`. 
For example: `Client Meeting:101, Research:102`

Notes:
1. Modifiers are not case sensitive (e.g. "Training" is equal to "TRAINING").
2. Trailing and leading spaces in modifiers are disregarded (e.g. " Research " is equal to "Research")
3. For multi-word modifier, spaces in between words are supported (e.g. "Client Meeting"). Thus, "Client Meeting" is 
considered as a different modifier to "ClientMeeting" 
 
## Patricia role type id
To know the correct role type id to use run this query on Patricia DB:  
  ```sql
  SELECT ROLE_TYPE_ID FROM CASE_ROLE_TYPE WHERE ROLE_TYPE_LABEL = 'Account Address';
  ```
  

## Logging to AWS CloudWatch

If configured, the Patricia Connector can send application logs to [AWS CloudWatch](https://aws.amazon.com/cloudwatch/). In order to do so, you must supply the following configuration through the following environment variables.

| Environment Variable  | Description                                          |
| --------------------- | ---------------------------------------------------- |
| AWS_ACCESS_KEY_ID     | AWS access key for account with access to CloudWatch |
| AWS_SECRET_ACCESS_KEY | Secret for the AWS access key                        |
| AWS_DEFAULT_REGION    | AWS region to log to                                 |

## Building

To build a Docker image of the WiseTime Patricia Connector, run:

```text
make docker
```
