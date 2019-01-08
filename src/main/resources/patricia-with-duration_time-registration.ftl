<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  ${timeRow.getSubmittedDate()?string.@printSubmittedDate_HH\:mm} hrs - [${timeRow.getDurationSecs()?string.@duration}] - ${timeRow.getActivity()} - ${timeRow.getDescription()}
 </#list>
</#if>