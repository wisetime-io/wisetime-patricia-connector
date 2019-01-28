<#assign rowTotalDuration = 0?number>
<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  <#assign rowTotalDuration += timeRow.getDurationSecs()>
  <#assign activityTime = timeRow.getActivityHour()?c + timeRow.getFirstObservedInHour()?left_pad(2, "0")>
  ${((activityTime?number)?string.@activityTimeUTC)?datetime.iso?string("HH:mm")} - ${timeRow.getDescription()}
 </#list>
</#if>
<#if getDescription()?has_content || getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
${'\n'}
Total worked time: ${rowTotalDuration?string.@duration}
Total chargeable time: ${getTotalDurationSecs()?string.@duration}
Experience factor: ${getUser().getExperienceWeightingPercent()}%
</#if>