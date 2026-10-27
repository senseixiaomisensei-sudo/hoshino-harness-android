package app.hoshino.harness
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.presentTurns
import org.junit.Assert.*
import org.junit.Test
class M2PresentationTest {
    @Test fun oneProcessPerTurnKeepsFinalAndApprovalOutside(){
        val user=ChatItem.User("u","task",emptyList(),false)
        val thinking=ChatItem.Thinking("t","thinking",false,0,10)
        val preamble=ChatItem.Assistant("p","Checking files",false)
        val tool=ChatItem.Tool("tool","read","read file","{}","result",ToolStatus.Success)
        val approval=ChatItem.Approval("a","approve","details","write",null)
        val final=ChatItem.Assistant("f","Done",false)
        val rows=presentTurns(listOf(user,thinking,preamble,tool,approval,final))
        assertEquals(1,rows.size);assertEquals(listOf(thinking,preamble,tool),rows[0].process);assertEquals(listOf(approval),rows[0].actions);assertEquals(listOf(final),rows[0].finalItems)
    }
}
