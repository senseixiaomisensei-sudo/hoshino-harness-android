// M2.7: deliver_file carries the dsh tool call id, so the Android side can place the file card
// under the turn that produced it (dsh ToolExecution.rootCallId == the session log's tool/call callId).
export interface DeliverArgs {sessionId?:unknown;name?:unknown;callId?:unknown}
export function deliverRequest(args:DeliverArgs,path:string,bytes:number){
 const callId=typeof args.callId==='string'&&args.callId.length>0&&args.callId.length<=200?args.callId:undefined;
 return {sessionId:args.sessionId,path,name:args.name??path.split('/').at(-1),bytes,...(callId?{callId}:{})};
}
