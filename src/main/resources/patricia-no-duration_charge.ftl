<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  <#assign activityTime = timeRow.getActivityHour()?c + timeRow.getFirstObservedInHour()?left_pad(2, "0")>
  ${((activityTime?number)?string.@activityTimeUTC)?datetime.iso?string("HH:mm")} - ${timeRow.getDescription()}
 </#list>
</#if>
<#if getDescription()?has_content || getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
${'\n'}
Total worked time: ${getTotalDuration(getTimeRows())?string.@duration}
Total chargeable time: ${getTotalDurationSecs()?string.@duration}
Experience factor: ${getUser().getExperienceWeightingPercent()}%
</#if>

<#function getTotalDuration timeRows>
  <#local rowTotalDuration = 0?number>
  <#list timeRows as timeRow>
    <#local rowTotalDuration += timeRow.getDurationSecs()>
  </#list>
  <#return rowTotalDuration />
</#function>