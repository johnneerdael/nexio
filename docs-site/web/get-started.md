## Who this is for
New users who want to set up and manage a NEXIO admin workspace.

## What you will build
A fully functional admin workspace with addons, catalogs, integrations, and a custom formatter.

## Before you start
1. Have a computer with internet access.
2. Install the NEXIO CLI.
3. Create a workspace folder.

## Addons
- Add a media source addon.
- Verify the addon appears in the workspace.

## Catalogs
- Create a catalog to organize media.
- Add items to the catalog.

## Integrations
- Connect a streaming service integration.
- Test the integration by fetching a stream list.

## Custom Formatter
- Create a simple formatter file.
- Apply it to a stream name.
- Verify the formatted output.

## Manage-from-Phone
- Open the NEXIO web UI on a phone.
- Use the mobile view to add or edit items.

## Check your result
- All components should be visible in the web UI.
- The formatter should change the stream name as expected.

## Common mistakes
- Forgetting to restart the workspace after adding an addon.
- Using an incorrect path for the formatter file.
- Not enabling the integration in the workspace config.

## Troubleshooting
- **Addon not listed**: Ensure the addon package is installed and the workspace config references it.
- **Formatter not applied**: Verify the formatter file has the correct extension (.fmt) and is referenced in the workspace config.
- **Integration fails**: Check network connectivity and API keys.

## Next step
Read the advanced formatter reference at [formatter.md](/web/admin-workspaces/formatter).