select s.schemaname,t.tablename,c.conglomeratenumber from sys.sysschemas s, sys.systables t, sys.sysconglomerates c where s.schemaid = t.schemaid and t.tableid = c.tableid and s.schemaname = 'APP' and t.tablename = 'USERTEST'
