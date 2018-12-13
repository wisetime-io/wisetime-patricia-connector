<#if getDescription()?has_content>${getDescription()}</#if>
<#list getTimeRows() as timeRow>
  ${timeRow.getActivity()} - ${timeRow.getDescription()}
</#list>