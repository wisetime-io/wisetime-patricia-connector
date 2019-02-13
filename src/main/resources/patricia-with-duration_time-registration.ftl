<#if getDescription()?has_content>${getDescription()}</#if>

<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
<#list getTimeRows() as timeRow>
${(timeRow.getActivityHour() % 100)?string["00"]}:${timeRow.getFirstObservedInHour()?string["00"]} hrs - [${timeRow.getDurationSecs()?string.@duration}] - ${timeRow.getActivity()} - ${timeRow.getDescription()!"N/A"}
</#list>
</#if>