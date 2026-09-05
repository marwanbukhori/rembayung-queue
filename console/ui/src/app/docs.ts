/** The shape GET /api/docs returns: enough to build a link, nothing that requires opening the file. */
export interface DocSummary {
  id: string;
  title: string;
  group: 'specs' | 'notes' | 'plans';
}
