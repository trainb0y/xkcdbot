package io.github.trainb0y.xkcdbot.extensions


import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.application.slash.publicSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.int
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalBoolean
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.components.applyComponents
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.components.linkButton
import com.kotlindiscord.kord.extensions.components.publicButton
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.types.respondEphemeral
import com.kotlindiscord.kord.extensions.utils.scheduling.Scheduler
import dev.kord.common.entity.Permission
import dev.kord.core.behavior.MessageBehavior
import dev.kord.core.behavior.edit
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.builder.message.modify.embed
import io.github.trainb0y.xkcdbot.logger
import io.github.trainb0y.xkcdbot.version
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours

class XKCDExtension : Extension() {
	override val name = "xkcd"

	/**
	 * A map of <comic name> to <comic number>
	 * @see updateComicNames
	 */
	private val comicNames = mutableMapOf<String, Int>()

	private val scheduler = Scheduler()

	data class XKCD(
		/**
		 * The number of this xkcd installment
		 */
		val num: Int,
		/**
		 * The title of this comic
		 */
		val title: String,
		/**
		 * The alt (hover) text of this comic
		 */
		val alt: String,
		/**
		 * The URL of the comic's image
		 */
		val img: String
	) {
		/**
		 * Apply this comic to [builder]
		 */
		fun applyEmbed(builder: EmbedBuilder) = builder.apply {
			this.title = this@XKCD.title
			this.footer {
				text = this@XKCD.alt
			}
			this.image = this@XKCD.img
			this.description = "xkcd #${this@XKCD.num}"
		}
	}

	/**
	 * Get the xkcd comic at [url]
	 */
	private fun getXKCD(url: String): XKCD? {
		logger.debug { "Attempting to get xkcd comic from $url" }
		val doc =
			try {
				Jsoup.connect(url).get()
			} catch (e: HttpStatusException) {
				logger.warn {"Couldn't get comic from $url! $e"}
				return null
			}

		val comic = doc?.select("#comic img")?.first()

		return XKCD(
			doc?.select("meta")?.get(3)?.attr("content")?.split("/")?.run { this[this.size - 2].toInt() } ?: -1,
			comic?.attr("alt") ?: "no comic title found",
			comic?.attr("title") ?: "no alt text found",
			comic?.let { "https:" + comic.attr("src") } ?: "https://imgs.xkcd.com/comics/not_available.png"
		)
	}

	/**
	 * Get the xkcd comic number [num]
	 */
	private fun getXKCD(num: Int) = getXKCD("https://xkcd.com/$num")


	/**
	 * Create an interactive message for [comic] using [message]
	 * The contents of [message] will be overwritten.
	 */
	suspend fun xkcdInteractiveMessage(comic: XKCD, message: MessageBehavior) {
		/**
		 * The curent comic displayed by this interactive message
		 */
		var currentNum = comic.num

		// Every time a button is clicked, the message clears and rewrites itself
		suspend fun applyNew(message: MessageBehavior) {
			logger.debug { "Applying comic $currentNum to $message" }
			message.edit {
				// Clear the previous content of this message
				this.embeds?.clear()
				this.content = null
				this.components?.clear() // Redo the buttons, otherwise the link buttons will be out of date

				// New embed
				this.embed { getXKCD(currentNum)!!.applyEmbed(this) }

				// New navigation buttons
				this.applyComponents(components {
					publicButton {
						label = "Previous"
						action {
							currentNum--
							applyNew(message)
						}
					}
					publicButton {
						label = "\uD83C\uDFB2" // dice emoji
						action {
							currentNum = Random.nextInt(1, getXKCD("https://xkcd.com/")!!.num)
							applyNew(message)
						}
					}
					publicButton {
						label = "Next"
						action {
							currentNum++
							applyNew(message)
						}
					}
					linkButton {
						label = "xkcd.com"
						url = "https://xkcd.com/${currentNum}"
					}
					linkButton {
						label = "explain"
						url = "https://www.explainxkcd.com/${currentNum}"
					}
				}
				)
			}
		}
		applyNew(message)
	}

	/**
	 * Update [comicNames] by parsing https://xkcd.com/archive/
	 */
	private fun updateComicNames() {
		logger.debug { "Updating comic name map" }
		comicNames.clear()
		val doc = Jsoup.connect("https://xkcd.com/archive/").get()
		val links = doc.select("a")
		for (link in links) {
			val name = link.text().lowercase()
			val num = try {
				link.attr("href").split("/")[1].toInt()
			} catch (e: NumberFormatException) {
				continue
			}
			comicNames[name] = num
		}
		logger.info {"Finished updating comic name map"}
	}


	override suspend fun setup() {
		updateComicNames()
		scheduler.schedule(12.hours, repeat = true, callback = ::updateComicNames)
		publicSlashCommand {
			name = "xkcd"
			description = "xkcd related commands"
			requirePermission(Permission.EmbedLinks)

			publicSubCommand {
				name = "latest"
				description = "Gets the latest xkcd"
				action {
					val xkcd = getXKCD("https://xkcd.com")!!
					val message = respond { embed { xkcd.applyEmbed(this) } }.message
					xkcdInteractiveMessage(xkcd, message)
				}
			}

			publicSubCommand {
				name = "random"
				description = "Get a random xkcd"
				action {
					val xkcd = getXKCD(Random.nextInt(1, getXKCD("https://xkcd.com/")!!.num))!!
					val message = respond { embed { xkcd.applyEmbed(this) } }.message
					xkcdInteractiveMessage(xkcd, message)
				}
			}

			publicSubCommand(::RangeCommandArgs) {
				name = "range"
				description = "Gets a range of xkcd comics"
				action {
					if (kotlin.math.abs(arguments.last - arguments.first) > 10) {
						respond {
							content = "Cannot get more than 10 comics at once!"
						}
						return@action
					}
					for (num in arguments.first..arguments.last) {
						val xkcd = getXKCD(num) ?: continue
						val message = respond { embed { xkcd.applyEmbed(this) } }.message
						if (arguments.buttons == true) xkcdInteractiveMessage(xkcd, message)
					}
				}
			}

			publicSubCommand(::SingleCommandArgs) {
				name = "get"
				description = "Get a specific xkcd comic"
				action {
					val xkcd = getXKCD(arguments.num) ?: run {
						respond {
							content = "Could not find comic #${arguments.num}"
						}
						return@action
					}
					val message = respond { embed { xkcd.applyEmbed(this) } }.message
					if (arguments.buttons == true) xkcdInteractiveMessage(xkcd, message)
				}
			}

			publicSubCommand(::LookupCommandArgs) {
				name = "lookup"
				description = "Get a comic by its name"
				action {
					val xkcd = getXKCD(comicNames[arguments.name.lowercase()] ?: -1) ?: run {
						respond {
							content = "Could not find comic!"
						}
						return@action
					}
					val message = respond { embed { xkcd.applyEmbed(this) } }.message
					if (arguments.buttons == true) xkcdInteractiveMessage(xkcd, message)
				}
			}

			ephemeralSubCommand {
				name = "help"
				description = "Bot information and help"
				action {
					respond {
						embed {
							title = "xkcd Bot v$version"
							field {
								name = "About"
								value = """
								This bot provides commands for the xkcd webcomic (https://xkcd.com/)
								It was written by @trainb0y#7688 out of boredom.
							""".trimIndent()
							}
							field {
								name = "Commands"
								value = """
								`/xkcd get <num>            `- Get a specific xkcd comic by its number
								`/xkcd range <first> <last> `- Get a range of xkcd comics from first to last
								`/xkcd random               `- Get a random xkcd comic
								`/xkcd lookup <name>	    `- Get a specific comic by its name
								`/xkcd latest     	        `- Get the latest xkcd
								
								Any parameter named "buttons" controls whether to attach the navigation buttons to the message.
								
							""".trimIndent()
							}
						}
						components {
							linkButton {
								label = "GitHub"
								url = "https://github.com/trainb0y/xkcdbot"
							}
							linkButton {
								label = "Report an Issue"
								url = "https://github.com/trainb0y/xkcdbot/issues"
							}
						}
					}
				}
			}
		}
	}

	inner class RangeCommandArgs : Arguments() {
		val first by int {
			name = "first"
			description = "The first comic to get"
		}
		val last by int {
			name = "last"
			description = "The last comic to get"
		}
		val buttons by optionalBoolean {
			name = "buttons"
			description = "Whether to show navigation buttons"
		}
	}

	inner class SingleCommandArgs : Arguments() {
		val num by int {
			name = "num"
			description = "The comic to get"
		}
		val buttons by optionalBoolean {
			name = "buttons"
			description = "Whether to show navigation buttons"
		}
	}

	inner class LookupCommandArgs : Arguments() {
		val name by string {
			name = "name"
			description = "The name of the comic to get"
		}
		val buttons by optionalBoolean {
			name = "buttons"
			description = "Whether to show navigation buttons"
		}
	}
}
