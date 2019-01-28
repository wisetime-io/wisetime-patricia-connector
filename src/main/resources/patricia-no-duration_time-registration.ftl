<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  <#assign activityTime = timeRow.getActivityHour()?c + timeRow.getFirstObservedInHour()?left_pad(2, "0")>
  ${((activityTime?number)?string.@activityTimeUTC)?datetime.iso?string("HH:mm")} - ${timeRow.getActivity()} - ${timeRow.getDescription()}
 </#list>
</#if>