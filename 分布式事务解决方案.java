------------------------
BASE					|
------------------------
	# CAP 理论中, 一般我们都是采用:AP(舍弃了一致性, 保证可用性)

	# 最终一致性, 在一定的时间内, 允许每个节点的数据不一致, 但是在经过一段时间后, 每个节点的数据必须是一致的、

	# Base理论
		* 它是CAP的一种扩展
		* 通过牺牲了强一致性, 来获取可用性

	Basically Available基本可用
		* 分布式系统出现故障的时候, 允许损失部分的可用功能, 保证核心的功能可用
		* 例如: 电商系统中的交易付款出现了问题, 但是商品可以正常的浏览

	Soft State 软状态
		* 由于不要求强一致性, 所以Base允许系统中出现中间状态(软状态)
		* 这个状态不影响系统可用性
		* 例如: 订单的"支付中...", "数据同步中"等等状态, 在数据最终一致性后修改为"成功"状态

	Eventually Consistent 最终一致性
		* 经过一段时间后, 所有节点的数据都将会达到一致
		* 但是需要一定的时间, 延迟

--------------------------------
XA 协议的实现 2 阶段提交		|
--------------------------------
	# 2PC
	# 角色
		* 一个协调者 coordinator
		* N个事务参数者 partcipant
	
	# 第一个阶段(请求/表决阶段)
		* coordinator 往所有的 partcipant 发送预处理请求:Prepare(有些资料也叫"Vote Request")
			- 就是问一下这些参与节点"这件事你们能不能处理成功了"
			- 此时这些参与者节点一般来说就会打开本地数据库事务,然后开始执行数据库本地事务
			- 但在执行完成后并不会立马提交数据库本地事务
		
		* partcipant 向 coordinator 报告:"我这边可以处理了"/"我这边不能处理"

		* 如果所有的 coordinator 都向协调者作了"Vote Commit"的反馈的话,那么此时流程就会进入第二个阶段了

	# 第二个阶段(提交/执行阶段) - 正常流程
		* 如果所有参与者节点都向协调者报告说"我这边可以处理",那么此时协调者就会向所有参与者节点发送"全局提交确认通知"(global_commit)
		* 此时参与者节点就会完成自身本地数据库事务的提交,并最终将提交结果回复"ack"消息给Coordinator
		* 然后Coordinator就会向调用方返回分布式事务处理完成的结果
	
	# 第二个阶段(提交/执行阶段) - 异常流程
		* 任意节点向协调者报告说"我这边不能处理"(向协调者节点反馈"Vote Abort"的消息)
		* 此时分布式事务协调者节点就会向所有的参与者节点发起事务回滚的消息(global_rollback)
		* 此时各个参与者节点就会回滚本地事务,释放资源,并且向协调者节点发送"ack"确认消息
		* 协调者节点就会向调用方返回分布式事务处理失败的结果
		
	# 两阶段提交协议中会遇到的一些问题
		* 性能问题
			* 从流程上我们可以看得出,其最大缺点就在于它的执行过程中间,节点都处于阻塞状态
			* 各个操作数据库的节点此时都占用着数据库资源,只有当所有节点准备完毕,事务协调者才会通知进行全局提交,参与者进行本地事务提交后才会释放资源
			* 这样的过程会比较漫长,对性能影响比较大


		* 协调者单点故障问题
			* 事务协调者是整个XA模型的核心,一旦事务协调者节点挂掉,会导致参与者收不到提交或回滚的通知,从而导致参与者节点始终处于事务无法完成的中间状态

		* 丢失消息导致的数据不一致问题
			* 在第二个阶段,如果发生局部网络问题,一部分事务参与者收到了提交消息,另一部分事务参与者没收到提交消息,那么就会导致节点间数据的不一致问题

--------------------------------
XA 协议的实现 3 阶段提交		|
--------------------------------
	# 3PC
		* 其在两阶段提交的基础上增加了CanCommit阶段,并引入了超时机制
		* 一旦事务参与者迟迟没有收到协调者的Commit请求,就会自动进行本地commit,这样相对有效地解决了协调者单点故障的问题
	
	# 第一阶段: CanCommit
		* 一种事务询问操作,事务的协调者向所有参与者询问"你们是否可以完成本次事务?"
		* 如果参与者节点认为自身可以完成事务就返回"YES",否则"NO"
		* 而在实际的场景中参与者节点会对自身逻辑进行事务尝试,其实说白了就是检查下自身状态的健康性,看有没有能力进行事务操作
	
	# 第二阶段: PreCommit
		* 在阶段一中,如果所有的参与者都返回Yes的话,那么就会进入PreCommit阶段进行事务预提交
		* 此时分布式事务协调者会向所有的参与者节点发送PreCommit请求,参与者收到后开始执行事务操作,并将Undo和Redo信息记录到事务日志中
		* 参与者执行完事务操作后（此时属于未提交事务的状态）,就会向协调者反馈"Ack"表示我已经准备好提交了,并等待协调者的下一步指令
		
		* 如果阶段一中有任何一个参与者节点返回的结果是No响应
		* 或者协调者在等待参与者节点反馈的过程中超时(2PC中只有协调者可以超时,参与者没有超时机制)
		* 整个分布式事务就会中断,协调者就会向所有的参与者发送"abort"请求

	# 第三阶段: DoCommit
		* 在阶段二中如果所有的参与者节点都可以进行PreCommit提交,那么协调者就会从"预提交状态" -> "提交状态"
		* 然后向所有的参与者节点发送"doCommit"请求,参与者节点在收到提交请求后就会各自执行事务提交操作,并向协调者节点反馈"Ack"消息
		* 协调者收到所有参与者的Ack消息后完成事务。

		*  如果有一个参与者节点未完成PreCommit的反馈或者反馈超时,那么协调者都会向所有的参与者节点发送"abort"请求,从而中断事务
	
	# 对比2PC
		* 相比较2PC而言,3PC对于协调者(Coordinator)和参与者（Partcipant）都设置了超时时间,而2PC只有协调者才拥有超时机制
		* 这个优化点,主要是避免了参与者在长时间无法与协调者节点通讯(协调者挂掉了)的情况下,无法释放资源的问题
		* 因为参与者自身拥有超时机制会在超时后,自动进行本地commit从而进行释放资源,而这种机制也侧面降低了整个事务的阻塞时间和范围

		* 通过CanCommit,PreCommit,DoCommit三个阶段的设计,相较于2PC而言,多设置了一个缓冲阶段保证了在最后提交阶段之前各参与节点的状态是一致的

		* 以上就是3PC相对于2PC的一个提高（相对缓解了2PC中的前两个问题）,但是3PC依然没有完全解决数据不一致的问题
	

		
--------------------------------
补偿事务(TCC)					|
--------------------------------
	# 需要自己编码去处理分布式事务的问题,它不是由DB提供的解决方案
	# 而是基于应用层的一种解决方案
		
	
	