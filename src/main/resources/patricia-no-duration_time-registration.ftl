<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  <#assign rowTotalDuration += timeRow.getDurationSecs()>
  ${timeRow.getSubmittedDate()?string.@printSubmittedDate_HH\:mm} - ${timeRow.getActivity()} - ${timeRow.getDescription()}
 </#list>
</#if>