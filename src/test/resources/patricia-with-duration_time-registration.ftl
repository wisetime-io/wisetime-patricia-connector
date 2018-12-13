<#if getDescription()?has_content>${getDescription()}</#if>
<#list getTimeRows() as timeRow>
  [${timeRow.getDurationSecs()?string.@duration}] - ${timeRow.getActivity()} - ${timeRow.getDescription()}
</#list>