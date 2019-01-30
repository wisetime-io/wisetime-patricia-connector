<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  ${(timeRow.getActivityHour()?c)?substring(8)}:${timeRow.getFirstObservedInHour()?left_pad(2, "0")} hrs - [${timeRow.getDurationSecs()?string.@duration}] - ${timeRow.getActivity()} - ${timeRow.getDescription()}
 </#list>
</#if>