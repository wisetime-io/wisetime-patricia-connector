<#assign rowTotalDuration = 0?number>
<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#assign actualDuration=0>
 <#list getTimeRows() as timeRow>
  <#assign rowTotalDuration += timeRow.getDurationSecs()>
  ${timeRow.getSubmittedDate()?string.@printSubmittedDate_HH\:mm} hrs - [${timeRow.getDurationSecs()?string.@duration}] - ${timeRow.getActivity()} - ${timeRow.getDescription()}
 </#list>
</#if>
<#if getDescription()?has_content || getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 ${'\n'}
Total worked time: ${rowTotalDuration?string.@duration}
Total chargeable time: ${getTotalDurationSecs()?string.@duration}
Experience factor: ${getUser().getExperienceWeightingPercent()}%
</#if>

