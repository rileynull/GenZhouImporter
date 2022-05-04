package rileynull

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.streams.toList

class SQLWriter(val targetCommName: String, val targetUserName: String) {
    private fun lit(str: String?): String {
        return if (str == null) "NULL" else "'${str.replace("'", "''")}'"
    }

    private fun lit(date: ZonedDateTime): String {
        return "TIMESTAMP '${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}'"
    }

    private fun mkurl(post: Post): String? {
        return if (post.is_self) null else post.url
    }

    private fun mkbody(post: Post): String {
        return "`${if (post.author == "[deleted]") post.author else "u/${post.author}"}" +
                " - from ${post.subreddit_name_prefixed}`  \n${post.selftext}"
    }

    private fun mkbody(comment: Comment): String {
        return "`${if (comment.author == "[deleted]") comment.author else "u/${comment.author}"}" +
                " - from ${comment.subreddit_name_prefixed}`  \n${comment.body}"
    }

    private fun mktitle(post: Post): String {
        // The schema allows us only 200 characters so we have to truncate, but Java's string representation doesn't
        // count Unicode code POINTS like postgres does, it counts specifically UTF-16 code UNITS.
        return if (post.title.codePointCount(0, post.title.length) <= 200) post.title
            else post.title.codePoints().limit(197).toList().fold(StringBuilder(), StringBuilder::appendCodePoint).toString() + "..."
    }

    /**
     * Cobble together a PL/pgSQL function to insert the given post and its replies into a Lemmy database.
     *
     * Note that `comments` must be topologically sorted with respect to the reply tree structure so that the reply ID
     * variable for the parent will have been set by the time children are inserted. This happens naturally with a pre-order
     * traversal as [DumpParser.accumulateComments] does, but you can't just supply an arbitrary list of comments.
     */
    operator fun invoke(post: Post, comments: List<Comment>): String {
        val sb = StringBuilder()

        sb.append("DECLARE comm_id INTEGER;\n")
        sb.append("DECLARE user_id INTEGER;\n")
        sb.append("DECLARE post_id INTEGER;\n")
        sb.append("DECLARE ${post.name}_id CONSTANT INTEGER := NULL;\n") // Top-level comments should have a null parent.
        for (comment in comments) {
            sb.append("DECLARE ${comment.name}_id INTEGER;\n") // Other parent IDs will be set upon parent insertion.
        }

        sb.append("BEGIN\n")

        // Set IDs for the target community and user.
        sb.append("SELECT id INTO STRICT comm_id FROM community WHERE name = '$targetCommName';\n")
        sb.append("SELECT id INTO STRICT user_id FROM person WHERE name = '$targetUserName';\n")

        // Insert the post.
        sb.append(
            "INSERT INTO post(name, url, body, creator_id, community_id, published) " +
            "VALUES (${lit(mktitle(post))}, ${lit(mkurl(post))}, ${lit(mkbody(post))}, user_id, comm_id, ${lit(post.created_utc)}) " +
            "RETURNING id INTO STRICT post_id;\n"
        )
        sb.append(
            "INSERT INTO post_like(post_id, person_id, score) " +
            "VALUES (post_id, user_id, ${post.score});\n"
        )

        // Insert the comments.
        for (comment in comments) {
            sb.append(
                "INSERT INTO comment(creator_id, post_id, parent_id, content, published) " +
                "VALUES (user_id, post_id, ${comment.parent_id}_id, ${lit(mkbody(comment))}, ${lit(comment.created_utc)}) " +
                "RETURNING id INTO STRICT ${comment.name}_id;\n"
            )
            sb.append(
                "INSERT INTO comment_like(person_id, comment_id, post_id, score) " +
                "VALUES (user_id, ${comment.name}_id, post_id, ${comment.score});\n"
            )
        }

        sb.append("END\n")

        return "DO ${lit(sb.toString())};\n\n"
    }
}
