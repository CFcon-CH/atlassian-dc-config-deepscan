/* Shared contract of the minimal report, exercised against each endpoint's actual embedded classes. */
Class minimal = getClass().classLoader.loadClass('MinimalReport')
Class overview = getClass().classLoader.loadClass('ConfigOverview')
Class cx = getClass().classLoader.loadClass('Cx')
int checks = 0
def verify = { boolean condition, String description ->
    assert condition : description
    checks++
}
String product = binding.getVariable('product')
String other = product == 'jira' ? 'confluence' : 'jira'
String base = product == 'jira' ? 'https://jira.example/jira/' : 'https://wiki.example/confluence'
List<List<String>> columns = minimal.columns(product)
String expectedHref = product == 'jira' ? 'https://jira.example/jira/projects/APL/issues' : 'https://wiki.example/confluence/display/APL/'
def item = { String key, String name, String category ->
    [key: [value: key], name: [value: name], lead: [value: 'Pius H\u00f6ltschi'], admins: [value: ['jira-admins', 'Daniel Tobler']],
     category: [value: category], issues: [value: '7226'], lastUpdate: [value: '30.10.2026'],
     apps: [value: ['Tempo', 'ScriptRunner']], appFields: [value: '4'], workflowExtensions: [value: '7', state: 'partial'],
     description: [value: 'Bereich f\u00fcr ART <Plattform>'], pages: [value: '412'],
     appMacros: [value: '3', state: 'partial'], userMacros: [state: 'notMeasured', note: 'the content index could not be read']]
}
def rowOf = { String body, String key ->
    Map model = minimal.read(body, product)
    int keyColumn = model.ids.key
    model.rows.find { minimal.rowKey(it[keyColumn], product) == key }
}
def columnOf = { String body, String heading ->
    Map model = minimal.read(body, product)
    model.header.findIndexOf { overview.text(it) == heading }
}

/* ---- A new table on the overview page --------------------------------------- */
String parent = '<p>KEEP intro &amp; notes</p><table><tbody><tr><td>KEEP other table</td></tr></tbody></table>'
String first = minimal.merge(parent, product, base, [item('APL', 'ART & Plattform', 'SWEP-Streams')], ['APL', 'OLD'] as Set)
verify(first.startsWith(parent), 'content outside the minimal table is kept')
verify(first.contains(cx.esc(minimal.NOTE)), 'the table says which columns are generated and which are kept')
Map model = minimal.read(first, product)
verify(model.header.size() == columns.size() + 2, 'a new table carries every generated column and the two default topics')
verify(model.header.collect { overview.text(it) } == columns.collect { it[1] } + ['Themengebiet 1', 'Themengebiet 2'],
    'generated headings in order, then Themengebiet 1 and 2')
verify(model.rows.size() == 1 && minimal.keys(first, product) == ['APL'], 'one row per object, keyed by its key')
List<String> apl = rowOf(first, 'APL')
verify(apl[0].contains('<a href="' + expectedHref + '">APL</a>'), 'the key links to the project or space itself')
verify(first.contains('ART &amp; Plattform'), 'values are escaped')
verify(apl[-1] == '' && apl[-2] == '', 'the topic cells of a new row are empty')
if (product == 'jira') {
    verify(first.contains('jira-admins, Daniel Tobler') && first.contains('Tempo, ScriptRunner'), 'lists are written as a list')
    verify(first.contains('>7 *<'), 'a lower bound carries its asterisk')
} else {
    verify(first.contains('3 *') && first.contains('not measured: the content index could not be read'), 'lower bound and not measured are stated')
}
verify(minimal.read(parent, product).header == null && minimal.keys(parent, product) == [], 'a page without the table holds no key')
verify(minimal.unconfirmed(first, product, null, [item('APL', 'ART & Plattform', 'SWEP-Streams')]) == [], 'the written row reads back')
verify(minimal.unconfirmed(first, product, null, [item('APL', 'ART & Plattform', 'Other')]) == ['APL'], 'a row with other values is not confirmed')
verify(minimal.unconfirmed(parent, product, null, [item('APL', 'A', 'B')]) == ['APL'], 'a missing table confirms nothing')

/* ---- Administrators work in the table ------------------------------------------ */
String lozenge = '<ac:structured-macro ac:name="status" ac:schema-version="1" ac:macro-id="m-1"><ac:parameter ac:name="colour">Green</ac:parameter><ac:parameter ac:name="title">KI</ac:parameter></ac:structured-macro>'
String edited = first.replace('<th>Themengebiet 1</th>', '<th><p><strong>KI-Governance</strong></p></th>')
    .replace('<td></td><td></td></tr>', '<td><p>' + lozenge + ' Pilot</p></td><td>Owner: Tobler</td></tr>')
    .replace('</th></tr>', '</th><th>Security</th></tr>').replace('</td></tr>', '</td><td>ISMS</td></tr>')
    .replace('<ac:structured-macro ac:name="anchor">', '<ac:structured-macro ac:name="anchor" ac:schema-version="1" ac:macro-id="a-1">')
verify(minimal.read(edited, product).rows.size() == 1, 'an edited table with renamed and added topic columns still reads')
String second = minimal.merge(edited, product, base, [item('APL', 'ART Plattform', 'Neue Kategorie'), item('KIGOV', 'KI Governance', 'safe')], ['APL', 'KIGOV'] as Set)
List<String> aplAgain = rowOf(second, 'APL')
verify(aplAgain.contains('<p>' + lozenge + ' Pilot</p>') && aplAgain.contains('Owner: Tobler') && aplAgain.contains('ISMS'),
    'every topic cell of an existing row is kept verbatim')
verify(second.contains('<th><p><strong>KI-Governance</strong></p></th>') && second.contains('<th>Security</th>'),
    'renamed and added topic headings are kept')
verify(second.contains('Neue Kategorie') && !second.contains('SWEP-Streams'), 'generated cells are rewritten')
List<String> kigov = rowOf(second, 'KIGOV')
verify(kigov != null && kigov[columnOf(second, 'Security')] == '' && kigov[columnOf(second, 'Themengebiet 2')] == '',
    'a new object gets a row with empty topic cells')
verify(minimal.keys(second, product) == ['APL', 'KIGOV'], 'existing rows keep their place, new ones are appended')
String third = minimal.merge(second, product, base, [item('KIGOV', 'KI Governance', 'safe')], ['APL', 'KIGOV'] as Set)
verify(rowOf(third, 'APL') == rowOf(second, 'APL'), 'a row that is not exported is left as it is')

/* Columns moved by an administrator stay where they were put. */
Map moved = minimal.read(third, product)
List<Integer> order = (0..<moved.header.size()).toList()
Collections.swap(order, 1, moved.header.size() - 1)
def swapRow = { List<String> cells -> order.collect { cells[it] } }
String movedBody = moved.before + minimal.marker(product, 'start') + '<table><tbody><tr>' + swapRow(moved.header).collect { '<th>' + it + '</th>' }.join('') + '</tr>' +
    moved.rows.collect { '<tr>' + swapRow(it).collect { c -> '<td>' + c + '</td>' }.join('') + '</tr>' }.join('') + '</tbody></table>' + minimal.marker(product, 'end') + moved.after
String afterMove = minimal.merge(movedBody, product, base, [item('APL', 'ART Plattform', 'Wieder neu')], ['APL', 'KIGOV'] as Set)
verify(overview.text(minimal.read(afterMove, product).header[1]) == 'Security' && afterMove.contains('Wieder neu'),
    'a moved column keeps its position and the generated cell is still found by its anchor')

/* ---- Objects that no longer exist --------------------------------------------- */
String marked = minimal.merge(third, product, base, [item('APL', 'ART Plattform', 'Neue Kategorie')], ['APL'] as Set)
verify(rowOf(marked, 'KIGOV')[0].contains(minimal.GONE) && !rowOf(marked, 'APL')[0].contains(minimal.GONE), 'a missing object keeps its row and is marked')
verify(rowOf(marked, 'KIGOV').contains('') , 'its topics stay')
String again = minimal.merge(marked, product, base, [], ['APL'] as Set)
verify(again.split(java.util.regex.Pattern.quote(minimal.GONE), -1).length - 1 == 1, 'marking is idempotent')
verify(!minimal.merge(marked.replace('<ac:structured-macro ac:name="status"', '<ac:structured-macro ac:macro-id="s-1" ac:name="status"'),
    product, base, [], ['APL', 'KIGOV'] as Set).contains(minimal.GONE), 'an object that exists again is unmarked, also after the editor stamped the mark')
verify(minimal.merge(third, product, base, [], null) == minimal.merge(third, product, base, [], ['APL', 'KIGOV'] as Set),
    'without a complete key list nothing is marked')

/* ---- Fail closed ---------------------------------------------------------------- */
def refused = { String body -> try { minimal.read(body, product); false } catch (IllegalArgumentException expected) { true } }
String headingOf = columns[2][1]
verify(refused(second.replace(minimal.prefix(product) + 'col-' + columns[2][0], 'removed-anchor')), 'a generated column without its anchor is refused: ' + headingOf)
verify(refused(second.replace('<td>Owner: Tobler</td>', '<td colspan="2">Owner: Tobler</td>')), 'merged cells are refused')
verify(refused(second.replace('<td>ISMS</td>', '<td><table><tbody><tr><td>n</td></tr></tbody></table></td>')), 'a nested table is refused')
verify(refused(second.replace('<td>ISMS</td>', '')), 'a row with a cell too few is refused')
verify(refused(second.replace(minimal.prefix(product) + 'row-' + minimal.encode('KIGOV'), minimal.prefix(product) + 'row-' + minimal.encode('APL'))), 'two rows with one key are refused')
verify(refused(second.replace(minimal.prefix(product) + 'row-' + minimal.encode('KIGOV'), 'x')), 'a row without its row anchor is refused')
verify(refused(second + minimal.marker(product, 'start')), 'a duplicated marker is refused')
boolean mergeRefused = false
try { minimal.merge(second.replace('</tbody>', ''), product, base, [item('APL', 'A', 'B')], null) } catch (IllegalArgumentException expected) { mergeRefused = true }
verify(mergeRefused, 'a table that cannot be read is never written')

/* ---- Next to the full overview and the other product ----------------------------- */
String full = overview.upsert(second, product, '601', 'Full export', 'DOC', 'Full', [[key: 'details', column: 'Details', label: 'Details', anchor: 'a1', count: '1 item']])
verify(overview.read(full, product, 'DOC').rows.size() == 1 && minimal.read(full, product).rows.size() == 2, 'the minimal table and the full overview coexist')
String both = minimal.merge(full, other, base, [item('ZZ', 'Other', 'x')], null)
verify(minimal.read(both, product).rows.size() == 2 && minimal.read(both, other).rows.size() == 1, 'the products keep separate tables')

/* ---- The write stages -------------------------------------------------------------- */
Map store = [ok: true, id: '10', title: 'Overview', spaceKey: 'DOC', version: 3, storage: parent]
def reader = { -> new LinkedHashMap(store) }
def writer = { Map page, String body -> store.storage = body; store.version = page.version + 1; [ok: true] }
List<Map> items = [item('APL', 'ART', 'C')]
def merger = { String body -> minimal.merge(body, product, base, items, null) }
def checker = { String body -> minimal.unconfirmed(body, product, base, items) }
Map updated = minimal.update('10', 'DOC', reader, writer, merger, checker)
verify(updated.state == 'updated' && store.version == 4 && minimal.keys(store.storage, product) == ['APL'], 'read, merge, one save and a confirmed read back')
verify(minimal.update('11', 'DOC', reader, writer, merger, checker).state == 'failed', 'another page identity is refused before any write')
Map refusedWrite = minimal.update('10', 'DOC', reader, { Map page, String body -> [ok: false, refused: true, error: 'conflict'] }, merger, checker)
verify(refusedWrite.state == 'failed' && refusedWrite.reason.contains('conflict'), 'a refused save is a failure')
Map silent = minimal.update('10', 'DOC', reader, { Map page, String body -> [ok: true] }, merger, checker)
verify(silent.state == 'unknown', 'a save that did not raise the version is not claimed')
store.storage = second.replace('</tbody>', '')
verify(minimal.update('10', 'DOC', reader, writer, merger, checker).state == 'failed', 'an unreadable table fails before the write')

/* ---- Cells --------------------------------------------------------------------- */
verify(minimal.cell(product, base, 'lead', [state: 'failed', note: 'NPE']) == '<em>unreadable: NPE</em>', 'a failed read is stated, never empty')
verify(minimal.cell(product, base, 'lead', [state: 'absent']) == '', 'an absent value is an empty cell')
verify(minimal.cell(product, base, 'key', [value: 'A B']).contains('A%20B'), 'the key is encoded into the link')

/* ---- Jira collectors, against stubs of the platform types they name ---------- */
if (product == 'jira') {
    File repo = new File(System.getProperty('repoRoot', '.'))
    String source = new File(repo, 'jira/jiraDCprojectConfig.groovy').getText('UTF-8')
    String functions = source.substring(source.indexOf('Map<String, Object> minimalContext() {'),
        source.indexOf('/* GET ?report=minimal. With project= one project'))
    def collectors = new GroovyShell(getClass().classLoader).evaluate('''
import org.codehaus.groovy.runtime.InvokerHelper
class ApplicationUser { String displayName; String getDisplayName() { displayName } }
class ProjectCategory { String name; String getName() { name } }
class Project {
    String key; String name; Long id; ApplicationUser lead; ProjectCategory category
    String getKey() { key }; String getName() { name }; Long getId() { id }
    ApplicationUser getProjectLead() { lead }; ProjectCategory getProjectCategoryObject() { category }
}
class ProjectRole {}
class RoleActor { String descriptor; String getDescriptor() { descriptor } }
class ProjectRoleActors { Set<RoleActor> actors; Set<RoleActor> getRoleActors() { actors } }
class ProjectRoleManager { Set<RoleActor> actors; ProjectRole getProjectRole(String n) { new ProjectRole() }; ProjectRoleActors getProjectRoleActors(ProjectRole role, Project project) { new ProjectRoleActors(actors: actors) } }
class ProjectManager { List<Project> active; List<Project> archived; List<Project> getProjectObjects() { active }; List<Project> getArchivedProjects() { archived } }
class PagerFilter { int max; PagerFilter(int max) { this.max = max } }
class FieldScreenLayoutItem { String fieldId; String getFieldId() { fieldId } }
class FieldScreenTab { List<FieldScreenLayoutItem> items; List<FieldScreenLayoutItem> getFieldScreenLayoutItems() { items } }
class FieldScreen { List<FieldScreenTab> tabs; List<FieldScreenTab> getTabs() { tabs } }
class FieldScreenSchemeItem { FieldScreen screen; FieldScreen getFieldScreen() { screen } }
class FieldScreenScheme { List<FieldScreenSchemeItem> items; Collection<FieldScreenSchemeItem> getFieldScreenSchemeItems() { items } }
class IssueTypeScreenSchemeEntity { FieldScreenScheme scheme; FieldScreenScheme getFieldScreenScheme() { scheme } }
class IssueTypeScreenScheme { Long id; List<IssueTypeScreenSchemeEntity> entities; Long getId() { id }; Collection<IssueTypeScreenSchemeEntity> getEntities() { entities } }
class IssueTypeScreenSchemeManager { IssueTypeScreenScheme scheme; int reads; IssueTypeScreenScheme getIssueTypeScreenScheme(Project p) { reads++; scheme } }
class CustomFieldType { String key; String getKey() { key } }
class CustomField { CustomFieldType type; CustomFieldType getCustomFieldType() { type } }
class CustomFieldManager { Map<String, CustomField> fields; CustomField getCustomFieldObject(String id) { fields.get(id) } }
class JiraWorkflow { List actions; Object descriptor; Collection getAllActions() { actions }; Object getDescriptor() { descriptor } }
class AssignableWorkflowScheme { Map<String, String> mappings; String fallback; Map<String, String> getMappings() { mappings }; String getActualDefaultWorkflow() { fallback } }
class WorkflowSchemeManager { AssignableWorkflowScheme scheme; AssignableWorkflowScheme getWorkflowSchemeObj(Project p) { scheme } }
class WorkflowManager { Map<String, JiraWorkflow> workflows; int reads; JiraWorkflow getWorkflow(String name) { reads++; workflows.get(name) } }
class ComponentAccessor {
    static Map<Class, Object> components = [:]
    static Object pluginAccessor; static Object issueManager; static Object authentication
    static IssueTypeScreenSchemeManager screens; static CustomFieldManager fields; static Object applicationProperties
    static <T> T getComponent(Class<T> type) { (T) components.get(type) }
    static Object getPluginAccessor() { pluginAccessor }
    static Object getIssueManager() { issueManager }
    static Object getJiraAuthenticationContext() { authentication }
    static IssueTypeScreenSchemeManager getIssueTypeScreenSchemeManager() { screens }
    static CustomFieldManager getCustomFieldManager() { fields }
}
''' + "class SearchService {}\nclass PluginMetadataManagerStub { Set system = [] as Set; boolean isSystemProvided(Object plugin) { system.contains(plugin.key) } }\n" + functions + '\nreturn this')
    def type = { String name -> collectors.class.classLoader.loadClass(name) }
    Class accessor = type('ComponentAccessor')
    def user = type('ApplicationUser').newInstance(displayName: 'Pius H\u00f6ltschi')
    def project = type('Project').newInstance(key: 'APL', name: 'ART Plattform', id: 10L, lead: user,
        category: type('ProjectCategory').newInstance(name: 'SWEP-Streams'))
    def actors = [type('RoleActor').newInstance(descriptor: 'jira-admins'), type('RoleActor').newInstance(descriptor: 'Daniel Tobler')] as Set

    /* Apps: Tempo (custom field type, post function by full.module.key), ScriptRunner (condition by
     * class.name), and the system workflow plugin, which is not an app. */
    def implementation = { String name -> new Expando(getImplementationClass: { -> collectors.class.classLoader.parseClass('class ' + name + ' {}') }, getModuleClass: { -> null }) }
    def plugins = [new Expando(key: 'is.origo.jira.tempo-plugin', getKey: { -> 'is.origo.jira.tempo-plugin' }, getName: { -> 'Tempo Timesheets' }, getModuleDescriptors: { -> [] }),
                   new Expando(key: 'com.onresolve.jira.groovy.groovyrunner', getKey: { -> 'com.onresolve.jira.groovy.groovyrunner' }, getName: { -> 'ScriptRunner' },
                               getModuleDescriptors: { -> [implementation('ScriptConditionImpl')] }),
                   new Expando(key: 'com.atlassian.jira.plugin.system.workflow', getKey: { -> 'com.atlassian.jira.plugin.system.workflow' }, getName: { -> 'Workflow' },
                               getModuleDescriptors: { -> [implementation('SystemPostFunction')] })]
    def metadata = type('PluginMetadataManagerStub').newInstance(system: ['com.atlassian.jira.plugin.system.workflow'] as Set)
    accessor.pluginAccessor = new Expando(getPlugins: { -> plugins })
    accessor.components = [(type('ProjectRoleManager')): type('ProjectRoleManager').newInstance(actors: actors)]
    def fieldOf = { String key -> type('CustomField').newInstance(type: type('CustomFieldType').newInstance(key: key)) }
    accessor.fields = type('CustomFieldManager').newInstance(fields: [
        customfield_1: fieldOf('is.origo.jira.tempo-plugin:account'), customfield_2: fieldOf('is.origo.jira.tempo-plugin:team'),
        customfield_3: fieldOf('com.atlassian.jira.plugin.system.customfieldtypes:textfield')])
    def tab = type('FieldScreenTab').newInstance(items: ['summary', 'customfield_1', 'customfield_2', 'customfield_3', 'customfield_1'].collect { type('FieldScreenLayoutItem').newInstance(fieldId: it) })
    def screenScheme = type('FieldScreenScheme').newInstance(items: [type('FieldScreenSchemeItem').newInstance(screen: type('FieldScreen').newInstance(tabs: [tab]))])
    accessor.screens = type('IssueTypeScreenSchemeManager').newInstance(scheme: type('IssueTypeScreenScheme').newInstance(id: 5L,
        entities: [type('IssueTypeScreenSchemeEntity').newInstance(scheme: screenScheme), type('IssueTypeScreenSchemeEntity').newInstance(scheme: screenScheme)]))
    def entry = { Map args -> new Expando(getArgs: { -> args }) }
    def action = new Expando(getValidators: { -> [] }, getPreFunctions: { -> [] },
        getPostFunctions: { -> [entry(['full.module.key': 'is.origo.jira.tempo-pluginlog-work-function']), entry(['class.name': 'SystemPostFunction'])] },
        getRestriction: { -> new Expando(getConditionsDescriptor: { -> new Expando(getConditions: { -> [entry(['class.name': 'ScriptConditionImpl']),
            new Expando(getConditions: { -> [entry(['class.name': 'ScriptConditionImpl'])] }, getArgs: { -> null })] }) }) },
        getUnconditionalResult: { -> new Expando(getPostFunctions: { -> [entry(['full.module.key': 'is.origo.jira.tempo-pluginother'])] }) },
        getConditionalResults: { -> [] })
    def workflow = type('JiraWorkflow').newInstance(actions: [action], descriptor: new Expando(getSteps: { -> [] }, getGlobalConditions: { -> null }))
    def workflows = type('WorkflowManager').newInstance(workflows: ['Software': workflow])
    accessor.components[type('WorkflowManager')] = workflows
    accessor.components[type('WorkflowSchemeManager')] = type('WorkflowSchemeManager').newInstance(scheme:
        type('AssignableWorkflowScheme').newInstance(mappings: ['10001': 'Software'], fallback: 'Software'))
    def issue = new Expando(getUpdated: { -> new java.sql.Timestamp(Date.parse('yyyy-MM-dd', '2026-10-30').time) })
    def jql = []
    def search = new Expando(parseQuery: { u, String q -> jql << q; [valid: true, query: 'Q'] },
        searchOverrideSecurity: { u, q, pager -> assert pager.max == 1; new Expando(getResults: { -> [issue] }) })
    accessor.components[type('SearchService')] = search
    accessor.issueManager = new Expando(getIssueCountForProject: { Long id -> id == 10L ? 7226L : -1L })
    accessor.authentication = new Expando(getLoggedInUser: { -> user })

    Map index = collectors.minimalAppIndex()
    index.pluginKeys.size()
    Map context = collectors.minimalContext()
    context.appIndex = index
    index.appNames.remove('com.atlassian.jira.plugin.system.workflow')
    Map row = collectors.minimalRow(project, context)
    verify(row.key.value == 'APL' && row.name.value == 'ART Plattform', 'key and name are read')
    verify(row.lead.value == 'Pius H\u00f6ltschi', 'the lead is read by display name')
    verify(row.admins.value == ['Daniel Tobler', 'jira-admins'], 'role actors are users and groups, sorted, not expanded')
    verify(row.category.value == 'SWEP-Streams', 'the category is read')
    verify(row.issues.value == '7226', 'the issue count comes from the IssueManager call')
    verify(row.lastUpdate.value == '30.10.2026' && row.lastUpdate.state == null, 'the latest update is one search, formatted as in the requirement')
    verify(jql == ['project = "APL" ORDER BY updated DESC'], 'the search names the project and sorts by update')
    verify(row.appFields.value == '2', 'distinct app custom fields on the screens, a field repeated or from Jira not counted')
    verify(row.workflowExtensions.value == '4', 'app conditions and functions, nested and in results, a system function not counted')
    verify(row.apps.value == ['ScriptRunner', 'Tempo Timesheets'], 'used apps come from fields and workflows')
    collectors.minimalRow(type('Project').newInstance(key: 'TWO', name: 'Two', id: 11L), context)
    verify(workflows.reads == 1, 'a workflow shared by projects is walked once per request')
    index.deadline = 0L
    Map late = collectors.minimalRow(project, context)
    verify(late.appFields.state == 'notMeasured' && late.apps.state == 'notMeasured', 'past the budget the app columns are not measured, not zero')
    index.deadline = Long.MAX_VALUE
    index.problem = 'the installed apps could not be listed'
    verify(collectors.minimalRow(project, context).workflowExtensions.note.contains('could not be listed'), 'an unreadable app list is stated')
    index.problem = null
    search.searchOverrideSecurity = { u, q, pager -> throw new MissingMethodException('searchOverrideSecurity', Object, [] as Object[]) }
    search.search = { u, q, pager -> new Expando(getResults: { -> [issue] }) }
    verify(collectors.minimalLastUpdate('APL', context).state == 'partial', 'a search under the administrator\'s security is a lower bound')
    search.search = { u, q, pager -> new Expando(getResults: { -> [] }) }
    verify(collectors.minimalLastUpdate('APL', context).state == 'absent', 'a project without issues has no latest update')
    context.searchService = null
    verify(collectors.minimalRow(project, context).lastUpdate.state == 'failed', 'a missing search service is unreadable, not empty')
    context.adminRole = null; context.roleProblem = 'This instance has no project role named Administrators.'
    context.issueManager = new Expando(getIssueCountForProject: { Long id -> throw new IllegalStateException('db down') })
    Map failed = collectors.minimalRow(project, context)
    verify(failed.admins.state == 'failed' && failed.admins.note.contains('Administrators'), 'a missing Administrators role is stated')
    verify(failed.issues.state == 'failed' && failed.issues.note.contains('db down'), 'a failed count is unreadable, never zero')
    def projects = type('ProjectManager').newInstance(active: [project], archived: [type('Project').newInstance(key: 'ARC')])
    verify(collectors.minimalExistingKeys(projects) == ['APL', 'ARC'] as Set, 'archived projects count as existing')
    verify(collectors.minimalExistingKeys(type('ProjectManager').newInstance(active: [project], archived: null)) == null,
        'an incomplete project list marks nothing')
}

/* ---- Confluence collectors, against stubs of the platform types they name ------ */
if (product == 'confluence') {
    File repo = new File(System.getProperty('repoRoot', '.'))
    String source = new File(repo, 'confluence/confluenceDCspaceConfig.groovy').getText('UTF-8')
    String scanSource = source.substring(source.indexOf('class MinimalScan {'),
        source.indexOf('/* =============================================================================\n * REST Endpoint'))
    GroovyClassLoader platform = new GroovyClassLoader(getClass().classLoader)
    platform.parseClass('''package com.atlassian.confluence.search.v2
interface SearchQuery {}
enum Index { CONTENT }
class SearchFieldMappings {
    static final Field SPACE_KEY = new Field(name: 'spacekey')
    static class Field { String name; String getName() { name } }
}
class SearchManager {
    Map<String, List<String>> hits = [:]
    Set<String> broken = [] as Set
    List<String> asked = []
    void scan(List indexes, SearchQuery query, Set<String> fields, java.util.function.Consumer<Map<String, String[]>> consumer) {
        String name = query.name
        asked << name
        if (broken.contains(name)) { throw new IllegalStateException('index down') }
        for (String space : hits.get(name) ?: []) { consumer.accept([spacekey: [space] as String[]]) }
    }
}
''')
    platform.parseClass('''package com.atlassian.confluence.search.v2.query
class MacroUsageQuery implements com.atlassian.confluence.search.v2.SearchQuery { String name; MacroUsageQuery(String name) { this.name = name } }
''')
    platform.parseClass('''package com.atlassian.confluence.macro.browser
interface MacroMetadataSource { Object getMacroMetadata() }
class MacroMetadataManager { List entries = []; Collection getAllMacroMetadata() { entries } }
''')
    platform.parseClass('''package com.atlassian.plugin
class PluginAccessor { List plugins = []; Collection getPlugins() { plugins } }
''')
    platform.parseClass('''package com.atlassian.plugin.metadata
class PluginMetadataManager { Set system = [] as Set; boolean isSystemProvided(Object plugin) { system.contains(plugin.key) } }
''')
    platform.parseClass('''package com.atlassian.confluence.renderer
class UserMacroLibrary { Map macros = [:]; Map getMacros() { macros } }
''')
    Class scanClass = platform.parseClass('''
import groovy.json.JsonOutput
import org.codehaus.groovy.runtime.InvokerHelper
import java.sql.Connection
import com.atlassian.confluence.search.v2.Index
import com.atlassian.confluence.search.v2.SearchFieldMappings
import com.atlassian.confluence.search.v2.SearchManager
import com.atlassian.confluence.search.v2.SearchQuery
class ComponentLocator { static Map<Class, Object> components = [:]; static Object getComponent(Class type) { components.get(type) } }
class Cw { static String firstIndexValue(Map<String, String[]> document, String field) { String[] v = document?.get(field); v ? v[0] : null } }
class Scan { static final String CATEGORY_NAMESPACE = 'team'; static final String ADMIN_PERMISSION = 'SETSPACEPERMISSIONS' }
class Db {
    static Map<String, List<Map<String, String>>> answers = [:]
    static Set<String> failing = [] as Set
    static String why(Throwable error) { error.message }
    static Map<String, Object> shape(Object connection, String table, List<String> columns) { [table: table] }
    static String shapeProblem(Map<String, Object> shape) { failing.contains(shape.table) ? 'the table ' + shape.table + ' moved' : null }
    static Map<String, Object> query(Object connection, String sql, List<String> arguments, List<String> columns, int cap) {
        String hit = answers.keySet().find { sql.contains(it) }
        [rows: hit == null ? [] : answers.get(hit), truncated: false, failure: null]
    }
}
''' + scanSource, 'MinimalScanUnderTest.groovy')
    scanClass = platform.loadClass('MinimalScan')
    def type = { String name -> platform.loadClass(name) }
    Class db = platform.loadClass('Db')
    java.sql.Connection connection = [:] as java.sql.Connection
    Class locator = platform.loadClass('ComponentLocator')
    db.answers = [
        'SELECT s.spacekey, s.spacename FROM spaces s ORDER BY': [[spacekey: 'APL', spacename: 'ART Plattform'], [spacekey: 'KIGOV', spacename: 'KI Governance']],
        'JOIN bodycontent': [[spacekey: 'APL', body: '<p>Bereich f&uuml;r ART Plattform</p><p>Bestellt durch Daniel Tobler TOOLS-3169</p>']],
        'JOIN content_label': [[spacekey: 'APL', name: 'safe'], [spacekey: 'APL', name: 'art']],
        'FROM spacepermissions': [[spacekey: 'APL', permgroupname: 'confluence-administrators'],
                                  [spacekey: 'APL', permusername: 'k1', username: 'dtobler', userdisplay: 'Daniel Tobler'],
                                  [spacekey: 'APL', permusername: 'k2', username: 'nodisplay'],
                                  [spacekey: 'KIGOV', permusername: 'k3']],
        'COUNT(c.contentid)': [[spacekey: 'APL', pages: '412']],
        'SELECT s.spacekey FROM spaces s': [[spacekey: 'APL'], [spacekey: 'KIGOV']]]
    Map estate = scanClass.rows(connection, null)
    verify(estate.failure == null && estate.rows.size() == 2 && estate.keys == ['APL', 'KIGOV'] as Set, 'every space is read in one pass')
    Map aplValues = estate.rows[0].minimal
    verify(aplValues.key.value == 'APL' && aplValues.name.value == 'ART Plattform', 'key and name are read')
    verify(aplValues.description.value.startsWith('Bereich f') && !aplValues.description.value.contains('<p>'), 'the description is plain text')
    verify(aplValues.category.value == ['art', 'safe'], 'categories are the team labels by name')
    verify(aplValues.admins.value == ['confluence-administrators', 'Daniel Tobler', 'nodisplay'], 'administrators are groups and users, display name first')
    verify(aplValues.pages.value == '412', 'the page count is read')
    Map kigovValues = estate.rows[1].minimal
    verify(kigovValues.pages.value == '0' && kigovValues.category.state == 'absent' && kigovValues.admins.value == ['k3'], 'a space without pages or categories is a measured zero')
    verify(scanClass.rows(connection, 'kigov').rows*.space.key == ['KIGOV'], 'one space can be read on its own')
    db.failing = ['cwd_user'] as Set
    Map partial = scanClass.rows(connection, null)
    verify(partial.rows[0].minimal.admins.state == 'failed' && partial.rows[0].minimal.pages.value == '412', 'a moved table costs its column only')
    db.failing = ['spaces'] as Set
    verify(scanClass.rows(connection, null).failure != null && scanClass.existingKeys(connection) == null, 'an unreadable space list is a failure, not an empty estate')
    db.failing = [] as Set
    verify(scanClass.existingKeys(connection) == ['APL', 'KIGOV'] as Set, 'the existing keys are every space key')

    def search = type('com.atlassian.confluence.search.v2.SearchManager').newInstance()
    search.hits = [drawio: ['APL', 'KIGOV'], 'drawio-sketch': ['APL'], gliffy: ['APL'], 'toc': ['APL'], 'my-macro': ['KIGOV']]
    Class sourceType = type('com.atlassian.confluence.macro.browser.MacroMetadataSource')
    def descriptor = { String name -> [getMacroMetadata: { -> new Expando(getMacroName: { -> name }) }].asType(sourceType) }
    def plugins = [new Expando(key: 'com.drawio', getKey: { -> 'com.drawio' }, getName: { -> 'Draw.io' }, getModuleDescriptors: { -> [descriptor('drawio'), new Object()] }),
                   new Expando(key: 'com.gliffy', getKey: { -> 'com.gliffy' }, getName: { -> 'Gliffy' }, getModuleDescriptors: { -> [descriptor('gliffy')] }),
                   new Expando(key: 'confluence.macros.advanced', getKey: { -> 'confluence.macros.advanced' }, getName: { -> 'Advanced Macros' }, getModuleDescriptors: { -> [descriptor('toc')] })]
    def pluginMetadata = type('com.atlassian.plugin.metadata.PluginMetadataManager').newInstance(system: ['confluence.macros.advanced'] as Set)
    def catalogue = type('com.atlassian.confluence.macro.browser.MacroMetadataManager').newInstance(
        entries: [new Expando(getMacroName: { -> 'drawio-sketch' }, getPluginKey: { -> 'com.drawio' })])
    def library = type('com.atlassian.confluence.renderer.UserMacroLibrary').newInstance(macros: ['my-macro': new Expando(getName: { -> 'my-macro' }), unused: null])
    locator.components = [(type('com.atlassian.confluence.search.v2.SearchManager')): search,
        (type('com.atlassian.plugin.PluginAccessor')): type('com.atlassian.plugin.PluginAccessor').newInstance(plugins: plugins),
        (type('com.atlassian.plugin.metadata.PluginMetadataManager')): pluginMetadata,
        (type('com.atlassian.confluence.macro.browser.MacroMetadataManager')): catalogue,
        (type('com.atlassian.confluence.renderer.UserMacroLibrary')): library]
    Map macros = scanClass.macros(0L)
    verify(macros.state == 'measured' && macros.note == null, 'a complete scan is measured: ' + macros.note)
    verify(!search.asked.contains('toc'), 'macros of system-provided apps are not scanned')
    verify(search.asked.containsAll(['drawio', 'drawio-sketch', 'gliffy', 'my-macro', 'unused']), 'descriptor, catalogue and user macro names are scanned')
    verify(macros.apps.APL as List == ['Draw.io', 'Gliffy'] && macros.apps.KIGOV as List == ['Draw.io'], 'used apps per space')
    verify(macros.appMacros.APL.size() == 3 && macros.appMacros.KIGOV.size() == 1, 'distinct app macros per space')
    verify(macros.userMacros.KIGOV as List == ['my-macro'] && macros.userMacros.APL == null, 'distinct user macros per space')
    Map values = [:]
    scanClass.addMacros(values, 'APL', macros)
    verify(values.apps.value == ['Draw.io', 'Gliffy'] && values.appMacros.value == '3' && values.userMacros.value == '0', 'the three columns of one space')
    search.broken = ['gliffy'] as Set
    Map broken = scanClass.macros(0L)
    verify(broken.state == 'partial' && broken.note.contains('1 macro name(s) whose scan failed'), 'a failed name makes the figures a lower bound')
    Map lower = [:]
    scanClass.addMacros(lower, 'KIGOV', broken)
    verify(MinimalReport.cell('confluence', null, 'appMacros', lower.appMacros) == '1 *', 'a lower bound is shown with its asterisk')
    Map none = [:]
    scanClass.addMacros(none, 'APL', [state: 'failed', note: 'this Confluence offers no macro usage query'])
    verify(none.apps.state == 'notMeasured' && MinimalReport.cell('confluence', null, 'userMacros', none.userMacros).contains('not measured'),
        'a scan that cannot run is not measured, never zero')
    locator.components.remove(type('com.atlassian.confluence.search.v2.SearchManager'))
    verify(scanClass.macros(0L).state == 'failed', 'without a search manager nothing is measured')
}


/* ---- The switch on the report page and in the bulk export ---------------------- */
def reportShell = getClass().classLoader.loadClass('Report').getDeclaredConstructor().newInstance()
if (product == 'jira') { reportShell.projectKey = 'APL'; reportShell.projectName = 'ART' } else { reportShell.spaceKey = 'APL'; reportShell.spaceName = 'ART' }
String reportPage = getClass().classLoader.loadClass('Render').html(reportShell, [:] as LinkedHashMap, false)
verify(reportPage.contains('id="exportMinimal"') && reportPage.contains('onchange="exportModeChanged()"'), 'the single export carries the minimal switch')
verify(reportPage.contains("action: 'minimal'") && reportPage.contains('function exportMinimalRow(payload)'), 'the single export posts the minimal action for this object')

println 'MINIMAL ' + product + ': ' + checks + ' checks passed'
