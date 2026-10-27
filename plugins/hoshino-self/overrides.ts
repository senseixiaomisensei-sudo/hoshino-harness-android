export interface UiNode {type:string;id?:string;text?:string;value?:unknown;bind?:string;when?:string;items?:unknown[];children?:UiNode[];onClick?:{command:string;arguments?:Record<string,unknown>};style?:Record<string,unknown>;animation?:Record<string,unknown>;path?:string}
export interface UiOverrides {
 accent?:string;bodyScale?:number;codeScale?:number;tagline?:string;
 capsules?:Array<{label:string;prompt:string}>;commands?:Array<{name:string;description:string;prompt:string}>;sidebar?:Array<{route:string;visible:boolean}>;
 version?:1;tokens?:Record<string,unknown>;themes?:Record<string,Record<string,unknown>>;theme?:string;
 slots?:Record<string,UiNode>;pages?:Array<{route:string;title:string;root:UiNode}>;
 state?:Record<string,unknown>;process?:{display?:'collapsed'|'current'|'expanded';types?:Record<string,boolean>};sidebarColumns?:number;
}
const string={type:'string',maxLength:32000};const text=(maxLength:number)=>({type:'string',minLength:1,maxLength});
const array=(items:unknown,maxItems:number)=>({type:'array',items,maxItems});
const object=(properties:Record<string,unknown>,required:string[]=[])=>({type:'object',properties,required,additionalProperties:false});
const nodeRef={$ref:'#/$defs/node'};
/** Published schema and validator share one source; extending the schema extends accepted configuration. */
export const UI_SCHEMA:Record<string,any>={
 $schema:'https://json-schema.org/draft/2020-12/schema',title:'Hoshino UI DSL 1',...object({
  version:{const:1},accent:{type:'string',pattern:'^#[0-9a-fA-F]{6}$'},bodyScale:{type:'number',minimum:0.8,maximum:1.5},codeScale:{type:'number',minimum:0.8,maximum:1.5},tagline:text(160),
  capsules:array(object({label:text(160),prompt:text(4000)},['label','prompt']),4),
  commands:array(object({name:{type:'string',pattern:'^[a-z][a-z0-9-]{0,31}$'},description:text(160),prompt:text(4000)},['name','description','prompt']),50),
  sidebar:array(object({route:text(100),visible:{type:'boolean'}},['route','visible']),100),
  tokens:{type:'object'},themes:{type:'object',additionalProperties:{type:'object'}},theme:text(100),
  slots:{type:'object',additionalProperties:nodeRef},pages:array(object({route:{type:'string',pattern:'^diy/[a-zA-Z0-9_/-]+$'},title:text(160),root:nodeRef},['route','title','root']),50),
  state:{type:'object'},sidebarColumns:{type:'integer',minimum:1,maximum:4},
  process:object({display:{enum:['collapsed','current','expanded']},types:{type:'object',additionalProperties:{type:'boolean'}}}),
 }),
 $defs:{node:object({type:{enum:['column','row','box','card','text','markdown','button','pill','tag','divider','spacer','input','switch','progress','icon','image','list','panel','slot','battery','status']},id:text(100),text:string,value:{},bind:text(200),when:text(200),items:array({},500),children:array(nodeRef,100),
  onClick:object({command:text(100),arguments:{type:'object'}},['command']),style:{type:'object'},animation:{type:'object'},path:text(1000)},['type'])},
};
export function validateSchema(value:unknown,schema:Record<string,any>=UI_SCHEMA,path='$',depth=0):void{
 if(depth>40)throw Error(`${path}: nesting exceeds 40; reduce layout depth`);
 if(schema.$ref){validateSchema(value,UI_SCHEMA.$defs[schema.$ref.split('/').at(-1)!],path,depth+1);return;}
 const type=Array.isArray(value)?'array':value===null?'null':typeof value;
 if(schema.type && !(schema.type==='integer' ? type==='number' && Number.isInteger(value) : type===schema.type))throw Error(`${path}: expected ${schema.type}; replace the value`);
 if(schema.const!==undefined && value!==schema.const)throw Error(`${path}: expected ${schema.const}`);
 if(schema.enum && !schema.enum.includes(value))throw Error(`${path}: choose ${schema.enum.join(', ')}`);
 if(typeof value==='number' && (!Number.isFinite(value) || value<(schema.minimum??-Infinity) || value>(schema.maximum??Infinity)))throw Error(`${path}: number outside ${schema.minimum}..${schema.maximum}`);
 if(typeof value==='string' && (value.length<(schema.minLength??0) || value.length>(schema.maxLength??1000000) || schema.pattern && !new RegExp(schema.pattern).test(value)))throw Error(`${path}: invalid text; check length or ${schema.pattern??'format'}`);
 if(Array.isArray(value)){if(value.length>(schema.maxItems??5000))throw Error(`${path}: too many items`);value.forEach((v,i)=>validateSchema(v,schema.items??{},`${path}[${i}]`,depth+1));}
 else if(value && typeof value==='object'){
  const o=value as Record<string,unknown>;
  for(const k of schema.required??[])if(!(k in o))throw Error(`${path}.${k}: required; add this field`);
  for(const [k,v]of Object.entries(o)){const child=schema.properties?.[k];if(!child && schema.additionalProperties===false)throw Error(`${path}.${k}: unknown field; use the published UI schema`);if(child || typeof schema.additionalProperties==='object')validateSchema(v,child??schema.additionalProperties,`${path}.${k}`,depth+1);}
 }
}
export function validateOverrides(value:unknown):UiOverrides{validateSchema(value);return structuredClone(value) as UiOverrides;}
